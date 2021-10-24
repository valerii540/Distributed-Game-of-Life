package vbosiak.common.models

import akka.actor.typed.ActorRef
import vbosiak.worker.actors.Worker.WorkerCommand

final case class WorkerIterationResult(ref: ActorRef[WorkerCommand], stats: WorkerIterationStats)

final case class WorkerIterationStats(population: Long)
