package challenge

import cats.effect.{IO, IOApp}
import challenge.api.TransferRoutes
import challenge.persistence.{Database, PostgresTransferRepository}
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder

object Main extends IOApp.Simple {
  private val databaseConfig = Database.Config(
    url = sys.env.getOrElse("CHALLENGE_DATABASE_URL", "jdbc:postgresql://localhost:5432/bulk_transfers"),
    user = sys.env.getOrElse("CHALLENGE_DATABASE_USER", "challenge"),
    password = sys.env.getOrElse("CHALLENGE_DATABASE_PASSWORD", "challenge")
  )
  private val port = sys.env.get("CHALLENGE_PORT").flatMap(_.toIntOption).getOrElse(8080)

  override val run: IO[Unit] =
    Database.transactor(databaseConfig).use { transactor =>
      val repository = new PostgresTransferRepository(transactor)
      val httpApp = new TransferRoutes(repository).routes.orNotFound

      EmberServerBuilder
        .default[IO]
        .withHost(Host.fromString("0.0.0.0").get)
        .withPort(Port.fromInt(port).get)
        .withHttpApp(httpApp)
        .build
        .useForever
    }
}
