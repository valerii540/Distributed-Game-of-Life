package vbosiak.master.controllers

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import vbosiak.master.actors.Master
import vbosiak.master.actors.Master.MasterCommand
import vbosiak.master.models.{Mode, UserParameters}

import scala.concurrent.duration.DurationInt

final class MasterController(master: ActorRef[MasterCommand])(implicit system: ActorSystem[Nothing]) extends PlayJsonSupport with LazyLogging {
  import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}

  def routes(managementRoutes: Route): Route =
    path("simulation" / "init") {
      post {
        entity(as[UserParameters]) { params =>
          if (params.mode == Mode.SoftTimed && params.delay.isEmpty)
            complete(StatusCodes.BadRequest, "Missing the 'delay' parameter for soft-timed mode")
          else {
            implicit val timeout: Timeout = 30.seconds
            onSuccess(master.ask(Master.PrepareSimulation(_, params))) {
              case Master.OK                          => complete(StatusCodes.OK)
              case Master.AlreadyRunning              => complete(StatusCodes.BadRequest, "Cluster already running a simulation")
              case Master.ImpossibleToProcess(reason) => complete(StatusCodes.BadRequest, reason)
              case Master.NoWorkersInCluster          => complete(StatusCodes.Conflict, "No workers connected to cluster")
            }
          }
        }
      }
    } ~
      path("simulation" / "next") {
        patch {
          implicit val timeout: Timeout = 5.seconds
          onSuccess(master.ask(Master.ManualTrigger)) {
            case Master.OK             => complete(StatusCodes.OK)
            case Master.AlreadyRunning => complete(StatusCodes.BadRequest, "The previous iteration is not completed yet")
            case _                     => complete(StatusCodes.InternalServerError)
          }
        }
      } ~
      path("simulation" / "workers" / "show") {
        get {
          master ! Master.ShowWorkersFields
          complete(StatusCodes.OK)
        }
      } ~
      path("simulation" / "reset") {
        patch {
          master ! Master.ResetSimulation
          complete(StatusCodes.OK)
        }
      } ~
      path("simulation" / "self-test") {
        get {
          master ! Master.ClusterSelfTest
          complete(StatusCodes.OK)
        }
      } ~
      path("cluster" / "status") {
        get {
          implicit val timeout: Timeout = 5.seconds
          onSuccess(master.ask(Master.TellClusterStatus))(complete(_))
        }
      } ~
      managementRoutes

}
