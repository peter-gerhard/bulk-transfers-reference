package challenge.persistence

import cats.effect.IO
import cats.syntax.all._
import challenge.domain.TransferBatch
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.Transactor
import org.typelevel.doobie.implicits._

sealed trait ProcessingResult
object ProcessingResult {
  case object Accepted extends ProcessingResult
  case object InsufficientFunds extends ProcessingResult
  case object UnknownAccount extends ProcessingResult
  case object KeyConflict extends ProcessingResult
}

trait TransferRepository {
  def process(idempotencyKey: String, batch: TransferBatch): IO[ProcessingResult]
}

final class SqliteTransferRepository(transactor: Transactor[IO]) extends TransferRepository {
  import ProcessingResult._

  override def process(idempotencyKey: String, batch: TransferBatch): IO[ProcessingResult] =
    program(idempotencyKey, batch).transact(transactor)

  private def program(idempotencyKey: String, batch: TransferBatch): ConnectionIO[ProcessingResult] = {
    val fingerprint = RequestFingerprint(batch)

    for {
      _ <- claimKey(idempotencyKey, fingerprint)
      record <- findKey(idempotencyKey)
      result <- record match {
        case Some((storedFingerprint, _)) if storedFingerprint != fingerprint =>
          (KeyConflict: ProcessingResult).pure[ConnectionIO]
        case Some((_, true)) =>
          (Accepted: ProcessingResult).pure[ConnectionIO]
        case Some((_, false)) =>
          processTransfers(idempotencyKey, batch)
        case None =>
          new IllegalStateException("idempotency key was not stored").raiseError[ConnectionIO, ProcessingResult]
      }
    } yield result
  }

  private def processTransfers(idempotencyKey: String, batch: TransferBatch): ConnectionIO[ProcessingResult] =
    findAccount(batch).flatMap {
      case None => (UnknownAccount: ProcessingResult).pure[ConnectionIO]
      case Some((_, balanceCents)) if batch.totalCents > BigInt(balanceCents) =>
        (InsufficientFunds: ProcessingResult).pure[ConnectionIO]
      case Some((accountId, _)) =>
        val total = batch.totalCents.longValue
        for {
          updated <- sql"""
            UPDATE bank_accounts
            SET balance_cents = balance_cents - $total
            WHERE id = $accountId AND balance_cents >= $total
          """.update.run
          result <-
            if (updated == 0) InsufficientFunds.pure[ConnectionIO]
            else
              for {
                _ <- insertTransfers(accountId, batch)
                _ <- markCompleted(idempotencyKey)
              } yield Accepted
        } yield result
    }

  private def claimKey(idempotencyKey: String, fingerprint: String): ConnectionIO[Int] =
    sql"""
      INSERT OR IGNORE INTO idempotency_requests
        (idempotency_key, request_fingerprint, completed)
      VALUES ($idempotencyKey, $fingerprint, 0)
    """.update.run

  private def findKey(idempotencyKey: String): ConnectionIO[Option[(String, Boolean)]] =
    sql"""
      SELECT request_fingerprint, completed
      FROM idempotency_requests
      WHERE idempotency_key = $idempotencyKey
    """.query[(String, Boolean)].option

  private def markCompleted(idempotencyKey: String): ConnectionIO[Int] =
    sql"""
      UPDATE idempotency_requests
      SET completed = 1
      WHERE idempotency_key = $idempotencyKey AND completed = 0
    """.update.run

  private def findAccount(batch: TransferBatch): ConnectionIO[Option[(Long, Long)]] =
    sql"""
      SELECT id, balance_cents
      FROM bank_accounts
      WHERE bic = ${batch.organizationBic} AND iban = ${batch.organizationIban}
    """.query[(Long, Long)].option

  private def insertTransfers(accountId: Long, batch: TransferBatch): ConnectionIO[Int] = {
    val rows = batch.transfers.map { transfer =>
      (
        transfer.counterpartyName,
        transfer.counterpartyIban,
        transfer.counterpartyBic,
        -transfer.amountCents,
        transfer.currency,
        accountId,
        transfer.description
      )
    }

    val statement =
      """INSERT INTO transactions
        |  (counterparty_name, counterparty_iban, counterparty_bic, amount_cents,
        |   amount_currency, bank_account_id, description)
        |VALUES (?, ?, ?, ?, ?, ?, ?)""".stripMargin

    org.typelevel.doobie.Update[(String, String, String, Long, String, Long, String)](statement).updateMany(rows)
  }
}

private object RequestFingerprint {
  def apply(batch: TransferBatch): String = {
    val digest = MessageDigest.getInstance("SHA-256")

    addString(digest, batch.organizationBic)
    addString(digest, batch.organizationIban)
    addInt(digest, batch.transfers.size)
    batch.transfers.foreach { transfer =>
      addLong(digest, transfer.amountCents)
      addString(digest, transfer.currency)
      addString(digest, transfer.counterpartyBic)
      addString(digest, transfer.counterpartyIban)
      addString(digest, transfer.counterpartyName)
      addString(digest, transfer.description)
    }

    HexFormat.of().formatHex(digest.digest())
  }

  private def addString(digest: MessageDigest, value: String): Unit = {
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    addInt(digest, bytes.length)
    digest.update(bytes)
  }

  private def addInt(digest: MessageDigest, value: Int): Unit =
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array())

  private def addLong(digest: MessageDigest, value: Long): Unit =
    digest.update(ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array())
}
