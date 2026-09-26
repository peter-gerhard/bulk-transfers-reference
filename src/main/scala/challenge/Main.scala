package challenge

import cats.effect.{IO, IOApp}
import challenge.api.TransferRoutes
import challenge.persistence.{Database, SqliteTransferRepository}
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder

object Main extends IOApp.Simple {
  private val databasePath = sys.env.getOrElse("CHALLENGE_DATABASE_PATH", "bulk-transfers.sqlite")
  private val port = sys.env.get("CHALLENGE_PORT").flatMap(_.toIntOption).getOrElse(8080)

  override val run: IO[Unit] = {
    val transactor = Database.transactor(databasePath)
    val repository = new SqliteTransferRepository(transactor)
    val httpApp = new TransferRoutes(repository).routes.orNotFound

    for {
      _ <- Database.initialize(transactor)
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(Host.fromString("0.0.0.0").get)
        .withPort(Port.fromInt(port).get)
        .withHttpApp(httpApp)
        .build
        .useForever
    } yield ()
  }
}
