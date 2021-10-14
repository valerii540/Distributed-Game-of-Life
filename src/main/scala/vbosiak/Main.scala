package vbosiak

import akka.actor.typed.ActorSystem
import com.typesafe.config.ConfigFactory
import vbosiak.actors.Guardian

import scala.io.StdIn

object Main extends App {
  val config = ConfigFactory.load()

  val system: ActorSystem[Unit] = ActorSystem(Guardian(config), config.getString("akka.actor.system-name"))

  //TODO: remove this development trick later
  StdIn.readLine()
  system.terminate()
}
