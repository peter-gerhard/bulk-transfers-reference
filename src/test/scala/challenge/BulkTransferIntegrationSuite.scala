package challenge

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import challenge.api.TransferRoutes
import challenge.persistence.{Database, SqliteTransferRepository}
import io.circe.{Json, parser}
import java.nio.file.Files
import org.http4s.{Header, Method, Request, Response, Status, Uri}
import org.http4s.circe.CirceEntityCodec._
import org.typelevel.ci.CIString
import org.typelevel.doobie.Transactor
import org.typelevel.doobie.implicits._

class BulkTransferIntegrationSuite extends munit.FunSuite {
  test("an affordable HTTP batch debits the account and persists every transfer") {
    withDatabase { transactor =>
      val app = new TransferRoutes(new SqliteTransferRepository(transactor)).routes.orNotFound
      val request = Request[IO](Method.POST, Uri.unsafeFromString("/transfers/bulk"))
        .putHeaders(Header.Raw(CIString("Idempotency-Key"), "walking-slice-1"))
        .withEntity(parser.parse(validRequest).toOption.get)

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
        .withEntity(parser.parse(validRequest.replace("14.5", "100.01")).toOption.get)

      for {
        response <- app.run(request)
        error <- errorCode(response)
        balance <- sql"SELECT balance_cents FROM bank_accounts WHERE id = 1".query[Long].unique.transact(transactor)
        transactionCount <- sql"SELECT COUNT(*) FROM transactions".query[Long].unique.transact(transactor)
      } yield {
        assertEquals(response.status, Status.UnprocessableContent)
        assertEquals(error, "insufficient_funds")
        assertEquals(balance, 10000L)
        assertEquals(transactionCount, 0L)
      }
    }
  }

  test("a batch spending the exact balance is accepted") {
    withDatabase { transactor =>
      val app = new TransferRoutes(new SqliteTransferRepository(transactor)).routes.orNotFound
      val request = bulkRequest(validRequest.replace("14.5", "99.5"), "exact-balance")

      for {
        response <- app.run(request)
        balance <- sql"SELECT balance_cents FROM bank_accounts WHERE id = 1".query[Long].unique.transact(transactor)
        transactions <- sql"SELECT amount_cents FROM transactions ORDER BY id".query[Long].to[List].transact(transactor)
      } yield {
        assertEquals(response.status, Status.Created)
        assertEquals(balance, 0L)
        assertEquals(transactions, List(-9950L, -50L))
      }
    }
  }

  test("malformed JSON returns 400 without reserving the idempotency key") {
    withDatabase { transactor =>
      val app = new TransferRoutes(new SqliteTransferRepository(transactor)).routes.orNotFound
      val request = Request[IO](Method.POST, Uri.unsafeFromString("/transfers/bulk"))
        .withEntity("{\"organization_bic\":")
        .putHeaders(
          Header.Raw(CIString("Content-Type"), "application/json"),
          Header.Raw(CIString("Idempotency-Key"), "malformed-json")
        )

      for {
        response <- app.run(request)
        error <- errorCode(response)
        balance <- sql"SELECT balance_cents FROM bank_accounts WHERE id = 1".query[Long].unique.transact(transactor)
        transactionCount <- sql"SELECT COUNT(*) FROM transactions".query[Long].unique.transact(transactor)
        idempotencyCount <- sql"SELECT COUNT(*) FROM idempotency_requests".query[Long].unique.transact(transactor)
      } yield {
        assertEquals(response.status, Status.BadRequest)
        assertEquals(error, "invalid_request_body")
        assertEquals(balance, 10000L)
        assertEquals(transactionCount, 0L)
        assertEquals(idempotencyCount, 0L)
      }
    }
  }

  test("an empty transfer batch returns 400 without reserving the idempotency key") {
    withDatabase { transactor =>
      val app = new TransferRoutes(new SqliteTransferRepository(transactor)).routes.orNotFound
      val request = bulkRequest(emptyBatchRequest, "empty-batch")

      for {
        response <- app.run(request)
        error <- errorCode(response)
        balance <- sql"SELECT balance_cents FROM bank_accounts WHERE id = 1".query[Long].unique.transact(transactor)
        transactionCount <- sql"SELECT COUNT(*) FROM transactions".query[Long].unique.transact(transactor)
        idempotencyCount <- sql"SELECT COUNT(*) FROM idempotency_requests".query[Long].unique.transact(transactor)
      } yield {
        assertEquals(response.status, Status.BadRequest)
        assertEquals(error, "invalid_request")
        assertEquals(balance, 10000L)
        assertEquals(transactionCount, 0L)
        assertEquals(idempotencyCount, 0L)
      }
    }
  }

  test("an unknown account returns 404 without changing financial state") {
    withDatabase { transactor =>
      val app = new TransferRoutes(new SqliteTransferRepository(transactor)).routes.orNotFound
      val request = Request[IO](Method.POST, Uri.unsafeFromString("/transfers/bulk"))
        .putHeaders(Header.Raw(CIString("Idempotency-Key"), "walking-slice-3"))
        .withEntity(parser.parse(validRequest.replace("demo bic", "unknown bic")).toOption.get)

      for {
        response <- app.run(request)
        error <- errorCode(response)
        balance <- sql"SELECT balance_cents FROM bank_accounts WHERE id = 1".query[Long].unique.transact(transactor)
        transactionCount <- sql"SELECT COUNT(*) FROM transactions".query[Long].unique.transact(transactor)
      } yield {
        assertEquals(response.status, Status.NotFound)
        assertEquals(error, "account_not_found")
        assertEquals(balance, 10000L)
        assertEquals(transactionCount, 0L)
      }
    }
  }

  test("a missing or blank idempotency key returns 400 without changing financial state") {
    withDatabase { transactor =>
      val app = new TransferRoutes(new SqliteTransferRepository(transactor)).routes.orNotFound
      val request = Request[IO](Method.POST, Uri.unsafeFromString("/transfers/bulk"))
        .withEntity(parser.parse(validRequest).toOption.get)
      val requestWithBlankKey = request.putHeaders(Header.Raw(CIString("Idempotency-Key"), "  "))

      for {
        missingKeyResponse <- app.run(request)
        blankKeyResponse <- app.run(requestWithBlankKey)
        missingKeyError <- errorCode(missingKeyResponse)
        blankKeyError <- errorCode(blankKeyResponse)
        balance <- sql"SELECT balance_cents FROM bank_accounts WHERE id = 1".query[Long].unique.transact(transactor)
        transactionCount <- sql"SELECT COUNT(*) FROM transactions".query[Long].unique.transact(transactor)
        idempotencyCount <- sql"SELECT COUNT(*) FROM idempotency_requests".query[Long].unique.transact(transactor)
      } yield {
        assertEquals(missingKeyResponse.status, Status.BadRequest)
        assertEquals(blankKeyResponse.status, Status.BadRequest)
        assertEquals(missingKeyError, "idempotency_key_required")
        assertEquals(blankKeyError, missingKeyError)
        assertEquals(balance, 10000L)
        assertEquals(transactionCount, 0L)
        assertEquals(idempotencyCount, 0L)
      }
    }
  }

  test("retrying a completed request replays 201 without applying another debit") {
    withDatabase { transactor =>
      val app = new TransferRoutes(new SqliteTransferRepository(transactor)).routes.orNotFound
      val firstRequest = bulkRequest(validRequest, "idempotent-success")
      val equivalentRetry = bulkRequest(validRequest.replace("14.5", "14.50"), "idempotent-success")

      for {
        firstResponse <- app.run(firstRequest)
        retryResponse <- app.run(equivalentRetry)
        balance <- sql"SELECT balance_cents FROM bank_accounts WHERE id = 1".query[Long].unique.transact(transactor)
        transactions <- sql"SELECT amount_cents FROM transactions ORDER BY id".query[Long].to[List].transact(transactor)
        completed <- sql"""
          SELECT completed FROM idempotency_requests WHERE idempotency_key = 'idempotent-success'
        """.query[Boolean].unique.transact(transactor)
      } yield {
        assertEquals(firstResponse.status, Status.Created)
        assertEquals(retryResponse.status, Status.Created)
        assertEquals(balance, 8500L)
        assertEquals(transactions, List(-1450L, -50L))
        assert(completed)
      }
    }
  }

  test("concurrent duplicate requests apply their financial effect once") {
    withDatabase { transactor =>
      val app = new TransferRoutes(new SqliteTransferRepository(transactor)).routes.orNotFound
      val request = bulkRequest(validRequest, "concurrent-duplicate")

      for {
        responses <- IO.both(app.run(request), app.run(request))
        balance <- sql"SELECT balance_cents FROM bank_accounts WHERE id = 1".query[Long].unique.transact(transactor)
        transactionCount <- sql"SELECT COUNT(*) FROM transactions".query[Long].unique.transact(transactor)
      } yield {
        assertEquals(responses._1.status, Status.Created)
        assertEquals(responses._2.status, Status.Created)
        assertEquals(balance, 8500L)
        assertEquals(transactionCount, 2L)
      }
    }
  }

  test("reusing a completed key for another request returns 409") {
    withDatabase { transactor =>
      val app = new TransferRoutes(new SqliteTransferRepository(transactor)).routes.orNotFound
      val firstRequest = bulkRequest(validRequest, "conflicting-key")
      val conflictingRequest = bulkRequest(validRequest.replace("14.5", "14.6"), "conflicting-key")

      for {
        firstResponse <- app.run(firstRequest)
        conflictResponse <- app.run(conflictingRequest)
        conflictError <- errorCode(conflictResponse)
        balance <- sql"SELECT balance_cents FROM bank_accounts WHERE id = 1".query[Long].unique.transact(transactor)
        transactionCount <- sql"SELECT COUNT(*) FROM transactions".query[Long].unique.transact(transactor)
      } yield {
        assertEquals(firstResponse.status, Status.Created)
        assertEquals(conflictResponse.status, Status.Conflict)
        assertEquals(conflictError, "idempotency_key_conflict")
        assertEquals(balance, 8500L)
        assertEquals(transactionCount, 2L)
      }
    }
  }

  test("retrying an insufficient-funds request re-evaluates the current balance") {
    withDatabase { transactor =>
      val app = new TransferRoutes(new SqliteTransferRepository(transactor)).routes.orNotFound
      val request = bulkRequest(validRequest.replace("14.5", "100.01"), "funds-can-change")
      val conflictingRequest = bulkRequest(validRequest.replace("14.5", "100.02"), "funds-can-change")

      for {
        firstResponse <- app.run(request)
        conflictResponse <- app.run(conflictingRequest)
        _ <- sql"UPDATE bank_accounts SET balance_cents = 20000 WHERE id = 1".update.run.transact(transactor)
        retryResponse <- app.run(request)
        balance <- sql"SELECT balance_cents FROM bank_accounts WHERE id = 1".query[Long].unique.transact(transactor)
        transactionCount <- sql"SELECT COUNT(*) FROM transactions".query[Long].unique.transact(transactor)
      } yield {
        assertEquals(firstResponse.status, Status.UnprocessableContent)
        assertEquals(conflictResponse.status, Status.Conflict)
        assertEquals(retryResponse.status, Status.Created)
        assertEquals(balance, 9949L)
        assertEquals(transactionCount, 2L)
      }
    }
  }

  test("retrying an unknown-account request re-evaluates whether the account exists") {
    withDatabase { transactor =>
      val app = new TransferRoutes(new SqliteTransferRepository(transactor)).routes.orNotFound
      val request = bulkRequest(validRequest.replace("demo bic", "new bank bic"), "account-can-appear")

      for {
        firstResponse <- app.run(request)
        _ <- sql"""
          INSERT INTO bank_accounts (id, organization_name, balance_cents, iban, bic)
          VALUES (2, 'NEW COMPANY', 2000, 'FR761234', 'NEWBANKBIC')
        """.update.run.transact(transactor)
        retryResponse <- app.run(request)
        balance <- sql"SELECT balance_cents FROM bank_accounts WHERE id = 2".query[Long].unique.transact(transactor)
      } yield {
        assertEquals(firstResponse.status, Status.NotFound)
        assertEquals(retryResponse.status, Status.Created)
        assertEquals(balance, 500L)
      }
    }
  }

  test("concurrent requests cannot collectively overdraw an account") {
    withDatabase { transactor =>
      val app = new TransferRoutes(new SqliteTransferRepository(transactor)).routes.orNotFound
      val body = validRequest.replace("14.5", "60")
      val firstRequest = bulkRequest(body, "concurrent-1")
      val secondRequest = bulkRequest(body, "concurrent-2")

      for {
        responses <- IO.both(app.run(firstRequest), app.run(secondRequest))
        balance <- sql"SELECT balance_cents FROM bank_accounts WHERE id = 1".query[Long].unique.transact(transactor)
        transactionCount <- sql"SELECT COUNT(*) FROM transactions".query[Long].unique.transact(transactor)
      } yield {
        assertEquals(Set(responses._1.status, responses._2.status), Set(Status.Created, Status.UnprocessableContent))
        assertEquals(balance, 3950L)
        assertEquals(transactionCount, 2L)
      }
    }
  }

  test("a database failure rolls back the debit, transfers, and idempotency reservation") {
    withDatabase { transactor =>
      val app = new TransferRoutes(new SqliteTransferRepository(transactor)).routes.orNotFound
      val request = bulkRequest(validRequest, "rollback-key")

      for {
        _ <- sql"""
          CREATE TRIGGER reject_supplier_b
          BEFORE INSERT ON transactions
          WHEN NEW.counterparty_name = 'Supplier B'
          BEGIN
            SELECT RAISE(ABORT, 'forced test failure');
          END
        """.update.run.transact(transactor)
        failedResponse <- app.run(request).attempt
        balanceAfterFailure <- sql"SELECT balance_cents FROM bank_accounts WHERE id = 1".query[Long].unique.transact(transactor)
        transactionsAfterFailure <- sql"SELECT COUNT(*) FROM transactions".query[Long].unique.transact(transactor)
        keyAfterFailure <- sql"""
          SELECT COUNT(*) FROM idempotency_requests WHERE idempotency_key = 'rollback-key'
        """.query[Long].unique.transact(transactor)
        _ <- sql"DROP TRIGGER reject_supplier_b".update.run.transact(transactor)
        retryResponse <- app.run(request)
      } yield {
        assert(failedResponse.isLeft)
        assertEquals(balanceAfterFailure, 10000L)
        assertEquals(transactionsAfterFailure, 0L)
        assertEquals(keyAfterFailure, 0L)
        assertEquals(retryResponse.status, Status.Created)
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

  private def bulkRequest(body: String, idempotencyKey: String): Request[IO] =
    Request[IO](Method.POST, Uri.unsafeFromString("/transfers/bulk"))
      .putHeaders(Header.Raw(CIString("Idempotency-Key"), idempotencyKey))
      .withEntity(parser.parse(body).toOption.get)

  private def errorCode(response: Response[IO]): IO[String] =
    response.as[Json].flatMap(json => IO.fromEither(json.hcursor.get[String]("code")))

  private val emptyBatchRequest =
    """{
      |  "organization_bic": "demo bic",
      |  "organization_iban": "fr76 1234",
      |  "credit_transfers": []
      |}""".stripMargin

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
