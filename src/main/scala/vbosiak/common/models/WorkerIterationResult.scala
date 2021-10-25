package vbosiak.common.models

import akka.actor.typed.ActorRef
import vbosiak.worker.actors.Worker.WorkerCommand

import scala.concurrent.duration.FiniteDuration

final case class WorkerIterationResult(ref: ActorRef[WorkerCommand], stats: WorkerIterationStats)

final case class WorkerIterationStats(duration: FiniteDuration, population: Long)
