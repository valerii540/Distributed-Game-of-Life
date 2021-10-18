package vbosiak.common.models

import akka.actor.typed.ActorRef
import vbosiak.worker.actors.Worker.WorkerCommand

final case class WorkerRep(actor: ActorRef[WorkerCommand], neighbors: Neighbors, capabilities: Capabilities)

final case class Neighbors(left: ActorRef[WorkerCommand], right: ActorRef[WorkerCommand])
