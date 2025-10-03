addSbtPlugin("org.scalaxb" % "sbt-scalaxb" % "1.12.0")
addSbtPlugin("com.github.sbt" %% "sbt-native-packager" % "1.10.4")

resolvers += "Akka library repository".at("https://repo.akka.io/maven")
addSbtPlugin("com.lightbend.akka.grpc" % "sbt-akka-grpc" % "2.4.3")
