libraryDependencies += "org.slf4j" % "slf4j-nop" % "1.7.25"
libraryDependencies += "org.eclipse.jgit" % "org.eclipse.jgit" % "4.9.0.201710071750-r"

scalacOptions ++= Seq("-feature","-deprecation")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.14")

addSbtPlugin("com.github.shmishleniy" % "sbt-deploy-ssh" % "0.1.4")

addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.3.4")

addSbtPlugin("org.foundweekends" % "sbt-bintray" % "latest.integration")
