ThisBuild / scalaVersion := "2.13.18"
ThisBuild / organization := "dev.challenge"
ThisBuild / version := "0.1.0-SNAPSHOT"

val http4sVersion = "0.23.37"
val circeVersion = "0.14.16"
val doobieVersion = "1.0.0-RC13"

lazy val root = (project in file("."))
  .settings(
    name := "bulk-transfers",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-encoding",
      "utf8",
      "-release:17"
    ),
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.typelevel" %% "doobie-core" % doobieVersion,
      "org.xerial" % "sqlite-jdbc" % "3.53.4.0",
      "org.slf4j" % "slf4j-simple" % "1.7.36" % Runtime,
      "org.scalameta" %% "munit" % "1.0.4" % Test
    )
  )
