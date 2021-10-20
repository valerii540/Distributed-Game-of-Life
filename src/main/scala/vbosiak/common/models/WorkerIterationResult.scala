package vbosiak.common.models

import akka.actor.typed.ActorRef
import vbosiak.worker.actors.Worker.WorkerCommand

final case class WorkerIterationResult(ref: ActorRef[WorkerCommand], result: Either[WorkerFailure, WorkerIterationStats])

final case class WorkerIterationStats(population: Long)
