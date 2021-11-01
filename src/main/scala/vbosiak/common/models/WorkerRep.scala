package vbosiak.common.models

import akka.actor.typed.ActorRef
import play.api.libs.json.{JsString, Json, Writes}
import vbosiak.worker.actors.Worker.WorkerCommand

final case class WorkerRep(actor: ActorRef[WorkerCommand], capabilities: Capabilities, neighbors: Option[Neighbors] = None) {
  override def equals(obj: Any): Boolean =
    obj match {
      case that: WorkerRep if that.actor.path.name == this.actor.path.name => true
      case _                                                               => false
    }
}

final case class Neighbors(left: ActorRef[WorkerCommand], right: ActorRef[WorkerCommand]) {
  override def toString: String = s"Neighbors(${left.path.name}, ${right.path.name})"
}

object WorkerRep {
  implicit val actorRef: Writes[ActorRef[WorkerCommand]] = Writes[ActorRef[WorkerCommand]](ref => JsString(ref.path.name))
  implicit val neighbors: Writes[Neighbors]              = Json.writes[Neighbors]
  implicit val workerRep: Writes[WorkerRep]              = Json.writes[WorkerRep]
}
