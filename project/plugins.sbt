addSbtPlugin("org.scalaxb" % "sbt-scalaxb" % "1.12.0")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.4")

// Migration auf Apache Pekko (Apache-2.0-Fork von Akka). Kein Lightbend-Resolver
// noetig, kein Akka-License-Key. Pekko-grpc 1.1.x ersetzt sbt-akka-grpc 2.4.x.
addSbtPlugin("org.apache.pekko" % "pekko-grpc-sbt-plugin" % "1.1.1")
