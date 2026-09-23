ThisBuild / scalaVersion := "2.13.18"
ThisBuild / organization := "dev.challenge"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "scala-test",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-encoding",
      "utf8",
      "-release:17"
    ),
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test
  )
