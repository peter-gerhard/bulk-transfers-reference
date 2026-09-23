package challenge

class GreetingSuite extends munit.FunSuite {
  test("builds a greeting") {
    assertEquals(Greeting.message("Scala"), "Hello, Scala!")
  }
}
