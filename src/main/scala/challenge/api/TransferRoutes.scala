package challenge.api

import cats.effect.IO
import challenge.persistence.{ProcessingResult, TransferRepository}
import io.circe.Json
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.typelevel.ci.CIString

final class TransferRoutes(repository: TransferRepository) {
  private val IdempotencyKey = CIString("Idempotency-Key")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "transfers" / "bulk" =>
      validateIdempotencyKey(request.headers.get(IdempotencyKey).map(_.head.value)) match {
        case Left(error) => BadRequest(errorJson(error))
        case Right(_) =>
          request.attemptAs[BulkTransferRequest].value.flatMap {
            case Left(_) => BadRequest(errorJson("request body is not valid JSON for this endpoint"))
            case Right(raw) =>
              BulkTransferRequest.validate(raw) match {
                case Left(error) => BadRequest(errorJson(error))
                case Right(batch) =>
                  repository.process(batch).flatMap {
                    case ProcessingResult.Accepted          => Created()
                    case ProcessingResult.InsufficientFunds => UnprocessableContent(errorJson("insufficient funds"))
                    case ProcessingResult.UnknownAccount    => NotFound(errorJson("account not found"))
                  }
              }
          }
      }
  }

  private def validateIdempotencyKey(value: Option[String]): Either[String, String] =
    value.map(_.trim).filter(_.nonEmpty).toRight("Idempotency-Key header is required")

  private def errorJson(message: String): Json =
    Json.obj("error" -> Json.fromString(message))
}
