package challenge.persistence

import cats.effect.{IO, Resource}
import org.typelevel.doobie.hikari.HikariTransactor
import org.typelevel.doobie.util.ExecutionContexts

object Database {
  final case class Config(url: String, user: String, password: String)

  def transactor(config: Config): Resource[IO, HikariTransactor[IO]] =
    for {
      connectEC <- ExecutionContexts.fixedThreadPool[IO](8)
      transactor <- HikariTransactor.newHikariTransactor[IO](
        driverClassName = "org.postgresql.Driver",
        url = config.url,
        user = config.user,
        pass = config.password,
        connectEC = connectEC
      )
    } yield transactor
}
