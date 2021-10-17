package vbosiak

import akka.actor.typed.ActorSystem
import vbosiak.common.actors.Guardian
import vbosiak.common.utils.ConfigProvider

import scala.io.StdIn

object Main extends App {
  private val config                    = ConfigProvider.config
  private val system: ActorSystem[Unit] = ActorSystem(Guardian(config), config.getString("akka.actor.system-name"))

  //TODO: remove this development trick later
  StdIn.readLine()
  system.terminate()
}
