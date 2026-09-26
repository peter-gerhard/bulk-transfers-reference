package challenge.domain

class MoneySuite extends munit.FunSuite {
  test("parses canonical decimal amounts exactly") {
    assertEquals(Money.parseCents("14.5"), Right(1450L))
    assertEquals(Money.parseCents("61238"), Right(6123800L))
    assertEquals(Money.parseCents("001.09"), Right(109L))
  }

  test("rejects non-canonical and non-positive amounts") {
    List("0", "0.00", ".5", "1.", "+1", "1e2", "-1", "1.001").foreach { amount =>
      assert(Money.parseCents(amount).isLeft, amount)
    }
  }

  test("accepts the largest representable cent amount and rejects the next cent") {
    assertEquals(Money.parseCents("92233720368547758.07"), Right(Long.MaxValue))
    assert(Money.parseCents("92233720368547758.08").isLeft)
  }
}
