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

  private sealed abstract class ErrorCode(val value: String)
  private object ErrorCode {
    case object IdempotencyKeyRequired extends ErrorCode("idempotency_key_required")
    case object InvalidRequestBody extends ErrorCode("invalid_request_body")
    case object InvalidRequest extends ErrorCode("invalid_request")
    case object AccountNotFound extends ErrorCode("account_not_found")
    case object IdempotencyKeyConflict extends ErrorCode("idempotency_key_conflict")
    case object InsufficientFunds extends ErrorCode("insufficient_funds")
  }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "transfers" / "bulk" =>
      validateIdempotencyKey(request.headers.get(IdempotencyKey).map(_.head.value)) match {
        case Left(errorCode) => BadRequest(errorJson(errorCode))
        case Right(idempotencyKey) =>
          request.attemptAs[BulkTransferRequest].value.flatMap {
            case Left(_) =>
              BadRequest(errorJson(ErrorCode.InvalidRequestBody))
            case Right(raw) =>
              BulkTransferRequest.validate(raw) match {
                case Left(_) => BadRequest(errorJson(ErrorCode.InvalidRequest))
                case Right(batch) =>
                  repository.process(idempotencyKey, batch).flatMap {
                    case ProcessingResult.Accepted          => Created()
                    case ProcessingResult.InsufficientFunds =>
                      UnprocessableContent(errorJson(ErrorCode.InsufficientFunds))
                    case ProcessingResult.UnknownAccount =>
                      NotFound(errorJson(ErrorCode.AccountNotFound))
                    case ProcessingResult.KeyConflict =>
                      Conflict(errorJson(ErrorCode.IdempotencyKeyConflict))
                  }
              }
          }
      }
  }

  private def validateIdempotencyKey(value: Option[String]): Either[ErrorCode, String] =
    value.map(_.trim).filter(_.nonEmpty).toRight(ErrorCode.IdempotencyKeyRequired)

  private def errorJson(code: ErrorCode): Json =
    Json.obj("code" -> Json.fromString(code.value))
}
