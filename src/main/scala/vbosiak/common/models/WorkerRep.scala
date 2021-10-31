package vbosiak.common.models

import akka.actor.typed.ActorRef
import play.api.libs.json.{JsString, Json, Writes}
import vbosiak.worker.actors.Worker.WorkerCommand

final case class WorkerRep(actor: ActorRef[WorkerCommand], capabilities: Capabilities, neighbors: Option[Neighbors] = None)

final case class Neighbors(left: ActorRef[WorkerCommand], right: ActorRef[WorkerCommand])

object WorkerRep {
  implicit val actorRef: Writes[ActorRef[WorkerCommand]] = Writes[ActorRef[WorkerCommand]](ref => JsString(ref.path.name))
  implicit val neighbors: Writes[Neighbors]              = Json.writes[Neighbors]
  implicit val workerRep: Writes[WorkerRep]              = Json.writes[WorkerRep]
}
