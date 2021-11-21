import mill._
import mill.scalalib._
import mill.scalalib.scalafmt._

object versions {
  val akka           = "2.6.17"
  val alpakka        = "3.0.3"
  val akkaHttp       = "10.2.7"
  val akkaManagement = "1.1.1"
  val logback        = "1.2.7"
  val scalaLogging   = "3.9.4"
  val enumeratum     = "1.7.0"
  val akkaPlayJson   = "1.38.2"
  val scalaParallel  = "1.0.4"

  val scalaTest = "3.2.10"
}

object app extends ScalaModule with ScalafmtModule {
  override def scalaVersion  = "2.13.7"
  override def scalacOptions = Seq("-feature", "-Ywarn-dead-code", "-Ywarn-unused", "-deprecation", "-unchecked", "target:11")

  override def forkArgs = Seq(s"-Xmx${sys.env.getOrElse("MAX_MEMORY", "4G")}")

  override def ivyDeps = {
    import versions._

    Agg(
      ivy"com.typesafe.akka::akka-cluster-typed:$akka",
      ivy"com.typesafe.akka::akka-cluster-sharding:$akka",
      ivy"com.typesafe.akka::akka-serialization-jackson:$akka",
      ivy"com.typesafe.akka::akka-actor-typed:$akka",
      ivy"com.typesafe.akka::akka-stream:$akka",
      ivy"com.lightbend.akka::akka-stream-alpakka-csv:$alpakka",
      ivy"com.typesafe.akka::akka-http:$akkaHttp",
      ivy"com.typesafe.akka::akka-http-spray-json:$akkaHttp",
      ivy"com.lightbend.akka.management::akka-management:$akkaManagement",
      ivy"com.lightbend.akka.management::akka-management-cluster-http:$akkaManagement",
      ivy"org.scala-lang.modules::scala-parallel-collections:$scalaParallel",
      ivy"com.beachape::enumeratum:$enumeratum",
      ivy"com.beachape::enumeratum-play-json:$enumeratum",
      ivy"de.heikoseeberger::akka-http-play-json:$akkaPlayJson",
      ivy"ch.qos.logback:logback-classic:$logback",
      ivy"com.typesafe.scala-logging::scala-logging:$scalaLogging"
    )
  }

  object test extends Tests with TestModule.ScalaTest {
    override def ivyDeps = {
      import versions._

      Agg(
        ivy"org.scalatest::scalatest-wordspec:$scalaTest",
        ivy"org.scalatest::scalatest-mustmatchers:$scalaTest",
        ivy"com.typesafe.akka::akka-actor-testkit-typed:$akka"
      )
    }
  }
}
