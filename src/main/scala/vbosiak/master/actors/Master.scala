package vbosiak.master.actors

import akka.Done
import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.typed._
import akka.util.Timeout
import vbosiak.common.models.{Capabilities, CborSerializable, Neighbors, WorkerRep}
import vbosiak.worker.actors.Worker
import vbosiak.worker.actors.Worker.{WorkerCommand, WorkerTopic}

import scala.concurrent.duration.DurationInt

object Master {
  final case class GameProperties(fieldSize: Long)

  /** Responses for MaterController */
  sealed trait ControllerResponse
  case object OK             extends ControllerResponse
  case object AlreadyRunning extends ControllerResponse

  /** Actor commands */
  sealed trait MasterCommand                                                                       extends CborSerializable
  final case class ClusterIsReady(workersCount: Int)                                               extends MasterCommand
  case object ClusterNotReady                                                                      extends MasterCommand
  final case class WorkerCapabilities(capabilities: Capabilities, worker: ActorRef[WorkerCommand]) extends MasterCommand
  final case class StartGame(replyTo: ActorRef[ControllerResponse])                                extends MasterCommand

  def apply(cluster: Cluster, workerTopic: ActorRef[WorkerTopic]): Behavior[MasterCommand] =
    Behaviors.setup { context =>
      context.log.info("Hello, I'm master {} at {}", context.self.path, cluster.selfMember.address)

      initialLifeCycle(workerTopic, 0, Vector.empty)
    }

  private def initialLifeCycle(
      workerTopic: ActorRef[WorkerTopic],
      workersCount: Int,
      workers: Vector[(ActorRef[WorkerCommand], Capabilities)]
  ): Behavior[MasterCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case ClusterIsReady(workersCount) =>
          context.log.info("Received readiness message from coordinator")
          workerTopic ! Topic.Publish(Worker.TellCapabilities(context.self))
          initialLifeCycle(workerTopic, workersCount, Vector.empty)

        case ClusterNotReady => initialLifeCycle(workerTopic, 0, Vector.empty)

        case WorkerCapabilities(capabilities, worker) =>
          context.log.debug("Received {} capabilities {}", worker, capabilities)
          if (workers.size + 1 == workersCount) {
            val allWorkers = workers :+ (worker, capabilities)
            val workersRep = allWorkers.zipWithIndex.map { case (w, i) =>
              val leftNeighbor  = if (allWorkers.isDefinedAt(i - 1)) allWorkers(i - 1)._1 else allWorkers.last._1
              val rightNeighbor = if (allWorkers.isDefinedAt(i + 1)) allWorkers(i + 1)._1 else allWorkers.head._1

              WorkerRep(w._1, Neighbors(leftNeighbor, rightNeighbor), w._2)
            }

            val weakestWorker = workersRep.minBy(_.capabilities.maxFiledSideSize)

            context.log.info(
              "Collected all info about workers. Weakest worker can handle only {}x{} field ({}GB). Waiting for start command",
              weakestWorker.capabilities.maxFiledSideSize,
              weakestWorker.capabilities.maxFiledSideSize,
              weakestWorker.capabilities.availableMemory / (1024 * 1024 * 1024)
            )
            idle(workersRep.toList, weakestWorker.capabilities.maxFiledSideSize)
          } else
            initialLifeCycle(workerTopic, workersCount, workers :+ (worker, capabilities))
      }
    }

  private def idle(workers: List[WorkerRep], maxFieldSize: Int): Behavior[MasterCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessagePartial { case StartGame(replyTo) =>
        import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
        implicit val timeout: Timeout             = 5.seconds
        implicit val system: ActorSystem[Nothing] = context.system

        replyTo ! OK

        //TODO: parallelize asking
        workers.map(worker => worker.actor.ask[Done](Worker.NewSimulation(_, maxFieldSize, worker.neighbors)))

        gameLifeCycle(workers, GameProperties(maxFieldSize))
      }
    }

  private def gameLifeCycle(workers: List[WorkerRep], gameProperties: GameProperties): Behavior[MasterCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage { case StartGame(replyTo) =>
        replyTo ! AlreadyRunning
        Behaviors.same
      }
    }
}
