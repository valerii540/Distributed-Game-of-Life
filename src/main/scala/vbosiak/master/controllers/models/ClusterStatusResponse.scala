package vbosiak.master.controllers.models

import akka.actor.typed.ActorRef
import play.api.libs.json.{JsString, Json, Writes}
import vbosiak.common.models.Capabilities
import vbosiak.master.models.Mode
import vbosiak.worker.actors.Worker.WorkerCommand

import java.util.UUID

private[master] final case class ClusterStatusResponse(
    status: ClusterStatus,
    mode: Option[Mode] = None,
    delay: Option[Int] = None,
    iteration: Option[Long] = None,
    workers: Option[List[WorkerResponse]] = None,
    workersRaw: Option[List[ActorRef[WorkerCommand]]] = None
)

private[master] final case class WorkerResponse(id: UUID, ref: ActorRef[WorkerCommand], neighbors: List[UUID], capabilities: Capabilities)

object ClusterStatusResponse {
  implicit val actorRef: Writes[ActorRef[WorkerCommand]]   = Writes[ActorRef[WorkerCommand]](ref => JsString(ref.toString))
  implicit val worker: Writes[WorkerResponse]              = Json.writes[WorkerResponse]
  implicit val clusterState: Writes[ClusterStatusResponse] = Json.writes[ClusterStatusResponse]
}
