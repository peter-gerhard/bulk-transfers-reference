package challenge

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import challenge.api.TransferRoutes
import challenge.persistence.{Database, SqliteTransferRepository}
import io.circe.parser.parse
import java.nio.file.Files
import org.http4s.{Header, Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityCodec._
import org.typelevel.ci.CIString
import org.typelevel.doobie.Transactor
import org.typelevel.doobie.implicits._

class WalkingSliceSuite extends munit.FunSuite {
  test("an affordable HTTP batch debits the account and persists every transfer") {
    withDatabase { transactor =>
      val app = new TransferRoutes(new SqliteTransferRepository(transactor)).routes.orNotFound
      val request = Request[IO](Method.POST, Uri.unsafeFromString("/transfers/bulk"))
        .putHeaders(Header.Raw(CIString("Idempotency-Key"), "walking-slice-1"))
        .withEntity(parse(validRequest).toOption.get)

      for {
        response <- app.run(request)
        balance <- sql"SELECT balance_cents FROM bank_accounts WHERE id = 1".query[Long].unique.transact(transactor)
        transactions <- sql"SELECT amount_cents FROM transactions ORDER BY id".query[Long].to[List].transact(transactor)
      } yield {
        assertEquals(response.status, Status.Created)
        assertEquals(balance, 8500L)
        assertEquals(transactions, List(-1450L, -50L))
      }
    }
  }

  test("an unaffordable HTTP batch returns 422 without changing financial state") {
    withDatabase { transactor =>
      val app = new TransferRoutes(new SqliteTransferRepository(transactor)).routes.orNotFound
      val request = Request[IO](Method.POST, Uri.unsafeFromString("/transfers/bulk"))
        .putHeaders(Header.Raw(CIString("Idempotency-Key"), "walking-slice-2"))
        .withEntity(parse(validRequest.replace("14.5", "100.01")).toOption.get)

      for {
        response <- app.run(request)
        balance <- sql"SELECT balance_cents FROM bank_accounts WHERE id = 1".query[Long].unique.transact(transactor)
        transactionCount <- sql"SELECT COUNT(*) FROM transactions".query[Long].unique.transact(transactor)
      } yield {
        assertEquals(response.status, Status.UnprocessableContent)
        assertEquals(balance, 10000L)
        assertEquals(transactionCount, 0L)
      }
    }
  }

  test("an unknown account returns 404 without changing financial state") {
    withDatabase { transactor =>
      val app = new TransferRoutes(new SqliteTransferRepository(transactor)).routes.orNotFound
      val request = Request[IO](Method.POST, Uri.unsafeFromString("/transfers/bulk"))
        .putHeaders(Header.Raw(CIString("Idempotency-Key"), "walking-slice-3"))
        .withEntity(parse(validRequest.replace("demo bic", "unknown bic")).toOption.get)

      for {
        response <- app.run(request)
        balance <- sql"SELECT balance_cents FROM bank_accounts WHERE id = 1".query[Long].unique.transact(transactor)
        transactionCount <- sql"SELECT COUNT(*) FROM transactions".query[Long].unique.transact(transactor)
      } yield {
        assertEquals(response.status, Status.NotFound)
        assertEquals(balance, 10000L)
        assertEquals(transactionCount, 0L)
      }
    }
  }

  test("a missing or blank idempotency key returns 400 without changing financial state") {
    withDatabase { transactor =>
      val app = new TransferRoutes(new SqliteTransferRepository(transactor)).routes.orNotFound
      val request = Request[IO](Method.POST, Uri.unsafeFromString("/transfers/bulk"))
        .withEntity(parse(validRequest).toOption.get)
      val requestWithBlankKey = request.putHeaders(Header.Raw(CIString("Idempotency-Key"), "  "))

      for {
        missingKeyResponse <- app.run(request)
        blankKeyResponse <- app.run(requestWithBlankKey)
        balance <- sql"SELECT balance_cents FROM bank_accounts WHERE id = 1".query[Long].unique.transact(transactor)
        transactionCount <- sql"SELECT COUNT(*) FROM transactions".query[Long].unique.transact(transactor)
      } yield {
        assertEquals(missingKeyResponse.status, Status.BadRequest)
        assertEquals(blankKeyResponse.status, Status.BadRequest)
        assertEquals(balance, 10000L)
        assertEquals(transactionCount, 0L)
      }
    }
  }

  private def withDatabase(test: Transactor[IO] => IO[Unit]): Unit = {
    val path = Files.createTempFile("bulk-transfers-test-", ".sqlite")
    val transactor = Database.transactor(path.toString)
    val setup = for {
      _ <- Database.initialize(transactor)
      _ <- sql"""
        INSERT INTO bank_accounts (id, organization_name, balance_cents, iban, bic)
        VALUES (1, 'ACME', 10000, 'FR761234', 'DEMOBIC')
      """.update.run.transact(transactor)
      _ <- test(transactor)
    } yield ()

    try setup.unsafeRunSync()
    finally Files.deleteIfExists(path)
  }

  private val validRequest =
    """{
      |  "organization_bic": "demo bic",
      |  "organization_iban": "fr76 1234",
      |  "credit_transfers": [
      |    {
      |      "amount": "14.5",
      |      "currency": "EUR",
      |      "counterparty_bic": "deut deff",
      |      "counterparty_iban": "de89 3704",
      |      "counterparty_name": "Supplier A",
      |      "description": "Invoice A"
      |    },
      |    {
      |      "amount": "0.50",
      |      "currency": "EUR",
      |      "counterparty_bic": "BNPAFRPP",
      |      "counterparty_iban": "FR14 2004",
      |      "counterparty_name": "Supplier B",
      |      "description": "Invoice B"
      |    }
      |  ]
      |}""".stripMargin
}
