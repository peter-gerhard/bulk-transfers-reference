package challenge

object Main extends App {
  println(Greeting.message("Example"))
}

object Greeting {
  def message(name: String): String = s"Hello, $name!"
}
