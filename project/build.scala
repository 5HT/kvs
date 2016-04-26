package kvs

import sbt._
import sbt.Keys._

object Build extends sbt.Build{
  lazy val root = Project(
    id = "kvs",
    base = file("."),
    settings = defaultSettings ++ publishSettings ++ Seq(
      libraryDependencies ++= Seq(
        "org.scalaz" %% "scalaz-core" % "7.2.0",
        "org.scala-lang.modules" %% "scala-pickling" % "0.11.0-M1",
        "com.playtech.mws" %% "rng" % "1.0-169-gabc6042",
        "junit" % "junit" % "4.12" % Test,
        "org.scalatest" %% "scalatest" % "2.2.4" % Test,
        "com.typesafe.akka" %% "akka-testkit" % "2.3.14" % Test
      )
    )
  )

  lazy val defaultSettings = Defaults.coreDefaultSettings ++ Seq(
    scalacOptions in Compile ++= Seq("-feature", "-deprecation", "-target:jvm-1.7"),
    javacOptions in Compile ++= Seq("-source", "1.7", "-target", "1.7")
  )

  lazy val buildSettings = Seq(
    organization := "com.playtech.mws",
    description := "Abstract Scala Types Key-Value Storage",
    version := org.eclipse.jgit.api.Git.open(file(".")).describe().call(),
    scalaVersion := "2.11.8")

  override lazy val settings = super.settings ++ buildSettings ++ resolverSettings ++ Seq(
    shellPrompt := (Project.extract(_).currentProject.id + " > "))

  lazy val resolverSettings = Seq(
    resolvers ++= Seq(
      Resolver.mavenLocal,
      "MWS Releases Resolver" at "http://ua-mws-nexus01.ee.playtech.corp/nexus/content/repositories/releases/"
    )
  )

  lazy val publishSettings = Seq(
    publishTo := Some("MWS Releases" at "http://ua-mws-nexus01.ee.playtech.corp/nexus/content/repositories/releases"),
    credentials += Credentials("Sonatype Nexus Repository Manager", "ua-mws-nexus01.ee.playtech.corp", "wpl-deployer", "aG1reeshie"),
    publishArtifact := true,
    publishArtifact in Compile := true,
    publishArtifact in Test := false,
    publishMavenStyle := true,
    pomIncludeRepository := (_ => false),
    publishLocal <<= publishM2,
    isSnapshot := true
  )
}
