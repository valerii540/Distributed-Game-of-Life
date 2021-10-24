package vbosiak.master.controllers

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import vbosiak.master.actors.Master
import vbosiak.master.actors.Master.MasterCommand
import vbosiak.master.models.{Mode, UserParameters}

import scala.concurrent.duration.DurationInt

final class MasterController(master: ActorRef[MasterCommand])(implicit system: ActorSystem[Nothing]) extends PlayJsonSupport {
  import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
  implicit val timeout: Timeout = 3.seconds

  def routes(managementRoutes: Route): Route =
    path("game" / "start") {
      post {
        entity(as[UserParameters]) { params =>
          if (params.mode == Mode.SoftTimed && params.delay.isEmpty)
            complete(StatusCodes.BadRequest, "Missing the 'delay' parameter for soft-timed mode")
          else
            onSuccess(master.ask(Master.StartGame(_, params))) {
              case Master.OK                 => complete(StatusCodes.OK)
              case Master.AlreadyRunning     => complete(StatusCodes.Conflict, "Cluster already running simulation")
              case Master.NoWorkersInCluster => complete(StatusCodes.Conflict, "No workers connected to cluster")
            }
        }
      }
    } ~ managementRoutes
}
