package vbosiak

import akka.actor.typed.ActorSystem
import akka.cluster.typed.{Cluster, Leave}
import vbosiak.common.actors.Guardian
import vbosiak.common.utils.ConfigProvider

import scala.io.StdIn

object Main extends App {
  private val config                    = ConfigProvider.config
  private val system: ActorSystem[Unit] = ActorSystem(Guardian(), config.getString("akka.actor.system-name"))

  //TODO: remove this development trick later
  StdIn.readLine()

  val cluster = Cluster(system)
  cluster.manager ! Leave(cluster.selfMember.address)
}
