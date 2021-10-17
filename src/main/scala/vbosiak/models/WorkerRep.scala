package vbosiak.models

import akka.actor.typed.ActorRef
import cats.data.Ior
import vbosiak.worker.actors.Worker.WorkerCommand
import vbosiak.common.utils.ResourcesInspector.Capabilities
import vbosiak.models.WorkerRep.Neighbors

final case class WorkerRep(actorRef: ActorRef[WorkerCommand], neighbors: Neighbors, capabilities: Capabilities)

object WorkerRep {
  type Neighbors = ActorRef[WorkerCommand] Ior ActorRef[WorkerCommand]
}
