package challenge.domain

import java.util.Locale

final case class Transfer(
    amountCents: Long,
    currency: String,
    counterpartyBic: String,
    counterpartyIban: String,
    counterpartyName: String,
    description: String
)

final case class TransferBatch(
    organizationBic: String,
    organizationIban: String,
    transfers: List[Transfer],
    totalCents: BigInt
)

object CanonicalBankIdentifier {
  def apply(value: String): String =
    value.filterNot(_.isWhitespace).toUpperCase(Locale.ROOT)
}

object Money {
  private val DecimalAmount = raw"([0-9]+)(?:\.([0-9]{1,2}))?".r
  private val MaxCents = BigInt(Long.MaxValue)

  def parseCents(input: String): Either[String, Long] = input.trim match {
    case DecimalAmount(whole, fraction) =>
      val fractionalCents = Option(fraction).fold(BigInt(0)) {
        case oneDigit if oneDigit.length == 1 => BigInt(oneDigit) * 10
        case twoDigits                       => BigInt(twoDigits)
      }
      val cents = BigInt(whole) * 100 + fractionalCents

      if (cents <= 0) Left("amount must be greater than zero")
      else if (cents > MaxCents) Left("amount is outside the supported range")
      else Right(cents.longValue)

    case _ => Left("amount must be a positive decimal with at most two decimal places")
  }
}
