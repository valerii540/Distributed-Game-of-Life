package vbosiak.models

import akka.actor.typed.ActorRef
import vbosiak.common.utils.ResourcesInspector.Capabilities
import vbosiak.worker.actors.Worker.WorkerCommand

final case class WorkerRep(actor: ActorRef[WorkerCommand], neighbors: Neighbors, capabilities: Capabilities)

final case class Neighbors(left: Option[ActorRef[WorkerCommand]], right: Option[ActorRef[WorkerCommand]])