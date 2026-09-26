package challenge.persistence

import cats.effect.IO
import cats.syntax.all._
import challenge.domain.TransferBatch
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.Transactor
import org.typelevel.doobie.implicits._

sealed trait ProcessingResult
object ProcessingResult {
  case object Accepted extends ProcessingResult
  case object InsufficientFunds extends ProcessingResult
  case object UnknownAccount extends ProcessingResult
}

trait TransferRepository {
  def process(batch: TransferBatch): IO[ProcessingResult]
}

final class SqliteTransferRepository(transactor: Transactor[IO]) extends TransferRepository {
  import ProcessingResult._

  override def process(batch: TransferBatch): IO[ProcessingResult] =
    program(batch).transact(transactor)

  private def program(batch: TransferBatch): ConnectionIO[ProcessingResult] =
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
            else insertTransfers(accountId, batch).as(Accepted)
        } yield result
    }

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
