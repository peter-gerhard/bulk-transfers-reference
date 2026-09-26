package challenge.api

import challenge.domain.{CanonicalBankIdentifier, Money, Transfer, TransferBatch}
import io.circe.Decoder

final case class CreditTransferRequest(
    amount: String,
    currency: String,
    counterpartyBic: String,
    counterpartyIban: String,
    counterpartyName: String,
    description: String
)

final case class BulkTransferRequest(
    organizationBic: String,
    organizationIban: String,
    creditTransfers: List[CreditTransferRequest]
)

object BulkTransferRequest {
  implicit val creditTransferDecoder: Decoder[CreditTransferRequest] =
    Decoder.forProduct6(
      "amount",
      "currency",
      "counterparty_bic",
      "counterparty_iban",
      "counterparty_name",
      "description"
    )(CreditTransferRequest.apply)

  implicit val decoder: Decoder[BulkTransferRequest] =
    Decoder.forProduct3("organization_bic", "organization_iban", "credit_transfers")(BulkTransferRequest.apply)

  def validate(request: BulkTransferRequest): Either[String, TransferBatch] = {
    val organizationBic = CanonicalBankIdentifier(request.organizationBic)
    val organizationIban = CanonicalBankIdentifier(request.organizationIban)

    for {
      _ <- requireNonEmpty(organizationBic, "organization_bic")
      _ <- requireNonEmpty(organizationIban, "organization_iban")
      _ <- Either.cond(request.creditTransfers.nonEmpty, (), "credit_transfers must not be empty")
      transfers <- request.creditTransfers.zipWithIndex.foldLeft[Either[String, List[Transfer]]](Right(Nil)) {
        case (result, (raw, index)) =>
          for {
            accumulated <- result
            transfer <- validateTransfer(raw).left.map(error => s"credit_transfers[$index]: $error")
          } yield transfer :: accumulated
      }
    } yield {
      val ordered = transfers.reverse
      TransferBatch(
        organizationBic,
        organizationIban,
        ordered,
        ordered.foldLeft(BigInt(0))((total, transfer) => total + transfer.amountCents)
      )
    }
  }

  private def validateTransfer(raw: CreditTransferRequest): Either[String, Transfer] = {
    val bic = CanonicalBankIdentifier(raw.counterpartyBic)
    val iban = CanonicalBankIdentifier(raw.counterpartyIban)

    for {
      amount <- Money.parseCents(raw.amount)
      _ <- Either.cond(raw.currency == "EUR", (), "currency must be EUR")
      _ <- requireNonEmpty(bic, "counterparty_bic")
      _ <- requireNonEmpty(iban, "counterparty_iban")
    } yield Transfer(amount, raw.currency, bic, iban, raw.counterpartyName, raw.description)
  }

  private def requireNonEmpty(value: String, field: String): Either[String, Unit] =
    Either.cond(value.nonEmpty, (), s"$field must not be empty")
}
