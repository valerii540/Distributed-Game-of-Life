name := "distributed-game-of-life"

version      := "0.1.0"
scalaVersion := "2.13.6"
organization := "vbosiak"

run / fork         := true
run / connectInput := true
run / javaOptions ++= Seq(s"-Xmx${sys.env("MAX_MEMORY")}")

scalacOptions ++= Seq("-feature", "-Ywarn-dead-code", "-Ywarn-unused", "-deprecation", "-unchecked", "target:11")

libraryDependencies ++= {
  val akka           = "2.6.17"
  val akkaManagement = "1.1.1"
  val logback        = "1.2.6"
  val scalaLogging   = "3.9.4"
  val cats           = "2.6.1"

  Seq(
    "com.typesafe.akka"             %% "akka-cluster-typed"           % akka,
    "com.typesafe.akka"             %% "akka-cluster-sharding"        % akka,
    "com.typesafe.akka"             %% "akka-serialization-jackson"   % akka,
    "com.lightbend.akka.management" %% "akka-management"              % akkaManagement,
    "com.lightbend.akka.management" %% "akka-management-cluster-http" % akkaManagement,
    "org.typelevel"                 %% "cats-core"                    % cats,
    "ch.qos.logback"                 % "logback-classic"              % logback,
    "com.typesafe.scala-logging"    %% "scala-logging"                % scalaLogging
  )
}
