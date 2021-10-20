package vbosiak.master.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.typed._
import akka.util.Timeout
import vbosiak.common.models._
import vbosiak.worker.actors.Worker
import vbosiak.worker.actors.Worker.{TellCapabilities, WorkerCommand, WorkerTopic}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object Master {
  final case class State(iteration: Long)

  /** Responses for MaterController */
  sealed trait ControllerResponse
  case object OK                 extends ControllerResponse
  case object AlreadyRunning     extends ControllerResponse
  case object NoWorkersInCluster extends ControllerResponse

  /** Actor commands */
  sealed trait MasterCommand extends CborSerializable

  final case class WorkerIsReady(ref: ActorRef[WorkerCommand])                                     extends MasterCommand
  case object ClusterNotReady                                                                      extends MasterCommand
  final case class WorkerCapabilities(capabilities: Capabilities, worker: ActorRef[WorkerCommand]) extends MasterCommand
  final case class StartGame(replyTo: ActorRef[ControllerResponse])                                extends MasterCommand

  private final case class WorkerIsReadyInternal(workerRef: ActorRef[WorkerCommand], capabilities: Capabilities) extends MasterCommand
  private final case class StartSimulation()                                                                     extends MasterCommand
  private final case class IterationDone(results: List[WorkerIterationResult])                                   extends MasterCommand

  def apply(cluster: Cluster, workerTopic: ActorRef[WorkerTopic]): Behavior[MasterCommand] =
    Behaviors.setup { context =>
      context.log.info("Hello, I'm master {} at {}", context.self.path, cluster.selfMember.address)

      setupLifeCycle(workerTopic, Vector.empty)
    }

  private def setupLifeCycle(
      workerTopic: ActorRef[WorkerTopic],
      workers: Vector[(ActorRef[WorkerCommand], Capabilities)]
  ): Behavior[MasterCommand] =
    Behaviors.setup { context =>
      implicit val askTimeout: Timeout = 10.seconds

      Behaviors.receiveMessage {
        case WorkerIsReady(workerRef) =>
          context.ask(workerRef, TellCapabilities) {
            case Success(capabilities: WorkerCapabilities) => WorkerIsReadyInternal(workerRef, capabilities.capabilities)
            case Failure(exception)                        => throw exception
          }
          Behaviors.same

        case WorkerIsReadyInternal(workerRef, capabilities) =>
          setupLifeCycle(workerTopic, workers :+ (workerRef, capabilities))

        case StartGame(controller) =>
          if (workers.isEmpty) {
            controller ! NoWorkersInCluster
            Behaviors.same
          } else {
            controller ! OK
            prepareSimulation(workers)
          }

        case ClusterNotReady => setupLifeCycle(workerTopic, Vector.empty)
      }
    }

  private def prepareSimulation(workers: Vector[(ActorRef[WorkerCommand], Capabilities)]): Behavior[MasterCommand] =
    Behaviors.setup { context =>
      import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
      implicit val ec: ExecutionContext         = context.system.executionContext
      implicit val timeout: Timeout             = 5.seconds
      implicit val system: ActorSystem[Nothing] = context.system

      val workersRep = workers.zipWithIndex.map { case (w, i) =>
        val leftNeighbor  = if (workers.isDefinedAt(i - 1)) workers(i - 1)._1 else workers.last._1
        val rightNeighbor = if (workers.isDefinedAt(i + 1)) workers(i + 1)._1 else workers.head._1

        WorkerRep(w._1, Neighbors(leftNeighbor, rightNeighbor), w._2)
      }

      val weakestWorker = workersRep.minBy(_.capabilities.maxFiledSideSize)
      context.log.info(
        "Collected all info about workers. Weakest worker can handle only {}x{} field ({}GB). Waiting for start command",
        weakestWorker.capabilities.maxFiledSideSize,
        weakestWorker.capabilities.maxFiledSideSize,
        weakestWorker.capabilities.availableMemory / (1024 * 1024 * 1024)
      )

      Future
        .traverse(workersRep) { worker =>
          worker.actor.ask(Worker.NewSimulation(_, weakestWorker.capabilities.maxFiledSideSize, worker.neighbors))
        }.onComplete {
          case Success(_)         => context.self ! StartSimulation()
          case Failure(exception) => throw exception
        }

      gameLifeCycle(workersRep.toList, State(0), weakestWorker.capabilities.maxFiledSideSize)
    }

  private def gameLifeCycle(workers: List[WorkerRep], state: State, fieldSize: Int): Behavior[MasterCommand] =
    Behaviors.setup { context =>
      import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
      implicit val ec: ExecutionContext         = context.system.executionContext
      implicit val timeout: Timeout             = 1.minute
      implicit val system: ActorSystem[Nothing] = context.system

      def nextIteration(): Behavior[MasterCommand] = {
        Future
          .traverse(workers)(_.actor.ask(Worker.NextIteration)).onComplete {
            case Success(stats)     => context.self ! IterationDone(stats)
            case Failure(exception) => throw exception
          }
        gameLifeCycle(workers, state.copy(iteration = state.iteration + 1), fieldSize)
      }

      Behaviors.receiveMessage {
        case StartSimulation() =>
          context.log.info("Starting simulation with {} workers and {}x{} squares size", workers.size, fieldSize, fieldSize)

          nextIteration()

        case IterationDone(results) =>
          if (results.forall(_.result.isRight)) {
            results.foreach { stat =>
              context.log.info("Worker {} completed iteration {} with {} population remaining", stat.ref, state.iteration, stat.result)
            }

            nextIteration()
          } else {
            results.foreach {
              case WorkerIterationResult(ref, Left(failure)) =>
                context.log.warn("Worker {} failed iteration {} because of {}", ref, state.iteration, failure)
              case WorkerIterationResult(ref, Right(stat))   =>
                context.log.info("Worker {} completed iteration {} with {} population remaining", ref, state.iteration, stat.population)
            }

            Behaviors.ignore
          }

        case StartGame(replyTo) =>
          replyTo ! AlreadyRunning
          Behaviors.same
      }
    }
}
