package vbosiak.master.controllers

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import vbosiak.master.actors.Master
import vbosiak.master.actors.Master.MasterCommand

import scala.concurrent.duration.DurationInt

final class MasterController(master: ActorRef[MasterCommand])(implicit system: ActorSystem[Nothing]) {
  import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}

  implicit val timeout: Timeout = 3.seconds

  def routes(managementRoutes: Route): Route =
    path("game" / "start") {
      post {
        onSuccess(master.ask(Master.StartGame)) {
          case Master.OK                 => complete(StatusCodes.OK)
          case Master.AlreadyRunning     => complete(StatusCodes.Conflict, "Cluster already running simulation")
          case Master.NoWorkersInCluster => complete(StatusCodes.Conflict, "No workers connected to cluster")
        }
      }
    } ~ managementRoutes
}
