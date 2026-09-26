package challenge.persistence

import cats.effect.IO
import org.typelevel.doobie.Transactor
import org.typelevel.doobie.implicits._
import org.sqlite.SQLiteConfig

object Database {
  def transactor(path: String): Transactor[IO] = {
    val config = new SQLiteConfig()
    config.setBusyTimeout(5000)
    config.enforceForeignKeys(true)

    Transactor.fromDriverManager[IO](
      driver = "org.sqlite.JDBC",
      url = s"jdbc:sqlite:$path",
      info = config.toProperties,
      logHandler = None
    )
  }

  def initialize(transactor: Transactor[IO]): IO[Unit] = {
    val schema = for {
      _ <- sql"""
        CREATE TABLE IF NOT EXISTS bank_accounts (
          id INTEGER PRIMARY KEY,
          organization_name TEXT NOT NULL,
          balance_cents INTEGER NOT NULL CHECK (balance_cents >= 0),
          iban TEXT NOT NULL,
          bic TEXT NOT NULL,
          UNIQUE (bic, iban)
        )
      """.update.run
      _ <- sql"""
        CREATE TABLE IF NOT EXISTS transactions (
          id INTEGER PRIMARY KEY,
          counterparty_name TEXT NOT NULL,
          counterparty_iban TEXT NOT NULL,
          counterparty_bic TEXT NOT NULL,
          amount_cents INTEGER NOT NULL CHECK (amount_cents < 0),
          amount_currency TEXT NOT NULL CHECK (amount_currency = 'EUR'),
          bank_account_id INTEGER NOT NULL REFERENCES bank_accounts(id),
          description TEXT NOT NULL
        )
      """.update.run
      _ <- sql"""
        CREATE TABLE IF NOT EXISTS idempotency_requests (
          idempotency_key TEXT PRIMARY KEY,
          request_fingerprint TEXT NOT NULL,
          completed INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1))
        )
      """.update.run
    } yield ()

    schema.transact(transactor)
  }
}
