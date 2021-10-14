name := "distributed-game-of-life"

version := "0.1.0"

scalaVersion := "2.13.6"

organization := "vbosiak"

libraryDependencies ++= {
  val akka                  = "2.6.16"
  val logback               = "1.2.6"
  val AkkaManagementVersion = "1.1.1"

  Seq(
    "com.typesafe.akka"             %% "akka-cluster-typed"           % akka,
    "com.typesafe.akka"             %% "akka-cluster-sharding"        % akka,
    "com.typesafe.akka"             %% "akka-serialization-jackson"   % akka,
    "ch.qos.logback"                 % "logback-classic"              % logback,
    "com.lightbend.akka.management" %% "akka-management"              % AkkaManagementVersion,
    "com.lightbend.akka.management" %% "akka-management-cluster-http" % AkkaManagementVersion
  )
}
