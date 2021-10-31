package vbosiak.master.controllers.models

import akka.actor.typed.ActorRef
import play.api.libs.json.{JsString, Json, Writes}
import vbosiak.common.models.{Capabilities, WorkerRep}
import vbosiak.master.models.Mode
import vbosiak.worker.actors.Worker.WorkerCommand

private[master] final case class ClusterStatusResponse(
    status: ClusterStatus,
    mode: Option[Mode] = None,
    delay: Option[Int] = None,
    iteration: Option[Long] = None,
    workers: Option[Set[WorkerResponse]] = None,
    workersRaw: Option[Set[WorkerRep]] = None
)

private[master] final case class WorkerResponse(ref: ActorRef[WorkerCommand], neighbors: List[String], capabilities: Capabilities, active: Boolean)

object ClusterStatusResponse {
  implicit val actorRef: Writes[ActorRef[WorkerCommand]]   = Writes[ActorRef[WorkerCommand]](ref => JsString(ref.path.name))
  implicit val worker: Writes[WorkerResponse]              = Json.writes[WorkerResponse]
  implicit val clusterState: Writes[ClusterStatusResponse] = Json.writes[ClusterStatusResponse]
}
