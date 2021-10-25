package vbosiak.common.models

import akka.actor.typed.ActorRef
import vbosiak.worker.actors.Worker.WorkerCommand

import java.util.UUID

final case class WorkerRep(id: UUID, actor: ActorRef[WorkerCommand], neighbors: Neighbors, capabilities: Capabilities)

final case class Neighbors(left: ActorRef[WorkerCommand], right: ActorRef[WorkerCommand])
