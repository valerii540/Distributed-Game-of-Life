name := "distributed-game-of-life"

version      := "0.1.0"
scalaVersion := "2.13.7"
organization := "vbosiak"

run / fork         := true
run / connectInput := true
run / javaOptions ++= Seq(s"-Xmx${sys.env.getOrElse("MAX_MEMORY", "4G")}")

scalacOptions ++= Seq("-feature", "-Ywarn-dead-code", "-Ywarn-unused", "-deprecation", "-unchecked", "target:11")

libraryDependencies ++= {
  val akka           = "2.6.17"
  val alpakka        = "3.0.3"
  val akkaHttp       = "10.2.6"
  val akkaManagement = "1.1.1"
  val logback        = "1.2.6"
  val scalaLogging   = "3.9.4"
  val enumeratum     = "1.7.0"
  val akkaPlayJson   = "1.38.2"
  val scalaParallel  = "1.0.4"

  val scalaTest = "3.2.10"

  Seq(
    "com.typesafe.akka"             %% "akka-cluster-typed"           % akka,
    "com.typesafe.akka"             %% "akka-cluster-sharding"        % akka,
    "com.typesafe.akka"             %% "akka-serialization-jackson"   % akka,
    "com.typesafe.akka"             %% "akka-actor-typed"             % akka,
    "com.typesafe.akka"             %% "akka-stream"                  % akka,
    "com.lightbend.akka"            %% "akka-stream-alpakka-csv"      % alpakka,
    "com.typesafe.akka"             %% "akka-http"                    % akkaHttp,
    "com.typesafe.akka"             %% "akka-http-spray-json"         % akkaHttp,
    "com.lightbend.akka.management" %% "akka-management"              % akkaManagement,
    "com.lightbend.akka.management" %% "akka-management-cluster-http" % akkaManagement,
    "org.scala-lang.modules"        %% "scala-parallel-collections"   % scalaParallel,
    "com.beachape"                  %% "enumeratum"                   % enumeratum,
    "com.beachape"                  %% "enumeratum-play-json"         % enumeratum,
    "de.heikoseeberger"             %% "akka-http-play-json"          % akkaPlayJson,
    "ch.qos.logback"                 % "logback-classic"              % logback,
    "com.typesafe.scala-logging"    %% "scala-logging"                % scalaLogging,
    "org.scalatest"                 %% "scalatest-wordspec"           % scalaTest % Test,
    "org.scalatest"                 %% "scalatest-mustmatchers"       % scalaTest % Test,
    "com.typesafe.akka"             %% "akka-actor-testkit-typed"     % akka      % Test
  )
}
