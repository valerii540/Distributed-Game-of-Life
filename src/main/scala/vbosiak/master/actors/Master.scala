package vbosiak.master.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.typed._
import akka.util.Timeout
import vbosiak.common.models._
import vbosiak.master.models.{Mode, UserParameters}
import vbosiak.worker.actors.Worker
import vbosiak.worker.actors.Worker.{TellCapabilities, WorkerCommand}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, DurationInt}
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
  final case class StartGame(replyTo: ActorRef[ControllerResponse], params: UserParameters)        extends MasterCommand

  private final case class WorkerIsReadyInternal(workerRef: ActorRef[WorkerCommand], capabilities: Capabilities) extends MasterCommand
  private final case class StartSimulation()                                                                     extends MasterCommand
  private final case class IterationDone(results: List[WorkerIterationResult])                                   extends MasterCommand

  def apply(cluster: Cluster): Behavior[MasterCommand] =
    Behaviors.setup { context =>
      context.log.info("Hello, I'm master {} at {}", context.self.path, cluster.selfMember.address)

      setupLifeCycle(Vector.empty)
    }

  private def setupLifeCycle(
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
          setupLifeCycle(workers :+ (workerRef, capabilities))

        case StartGame(controller, params) =>
          if (workers.isEmpty) {
            controller ! NoWorkersInCluster
            Behaviors.same
          } else {
            controller ! OK
            prepareSimulation(workers, params)
          }

        case ClusterNotReady => setupLifeCycle(Vector.empty)
      }
    }

  private def prepareSimulation(workers: Vector[(ActorRef[WorkerCommand], Capabilities)], params: UserParameters): Behavior[MasterCommand] =
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

      params match {
        case UserParameters(Mode.Fastest, _)       =>
          fastestModeBehaviour(workersRep.toList, State(0), weakestWorker.capabilities.maxFiledSideSize)
        case UserParameters(Mode.Manual, _)        =>
          manualModeBehaviour(workersRep.toList, State(0), weakestWorker.capabilities.maxFiledSideSize)
        case UserParameters(Mode.SoftTimed, delay) =>
          softTimedBehaviour(workersRep.toList, State(0), weakestWorker.capabilities.maxFiledSideSize, Duration(delay.get, TimeUnit.SECONDS))
      }
    }

  private def fastestModeBehaviour(workers: List[WorkerRep], state: State, fieldSize: Int): Behavior[MasterCommand] =
    Behaviors.setup { context =>
      import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
      implicit val ec: ExecutionContext         = context.system.executionContext
      implicit val timeout: Timeout             = 1.minute
      implicit val system: ActorSystem[Nothing] = context.system

      def nextIteration(): Behavior[MasterCommand] = {
        Future
          .traverse(workers)(_.actor.ask(Worker.NextIteration))
          .flatMap(stats => Future.traverse(workers)(_.actor.ask(Worker.SwapState)).map(_ => stats))
          .onComplete {
            case Success(stats)     => context.self ! IterationDone(stats)
            case Failure(exception) => throw exception
          }
        fastestModeBehaviour(workers, state.copy(iteration = state.iteration + 1), fieldSize)
      }

      Behaviors.receiveMessage {
        case StartSimulation() =>
          context.log.info("Starting simulation with {} workers and {}x{} squares size", workers.size, fieldSize, fieldSize)

          nextIteration()

        case IterationDone(results) =>
          results.foreach { result =>
            context.log.info("Worker {} completed iteration {} with {} population remaining", result.ref, state.iteration, result.stats.population)
          }

          nextIteration()

        case StartGame(replyTo, _) =>
          replyTo ! AlreadyRunning
          Behaviors.same
      }
    }

  private def manualModeBehaviour(workers: List[WorkerRep], state: State, fieldSize: Int): Behavior[MasterCommand] = ???

  private def softTimedBehaviour(workers: List[WorkerRep], state: State, fieldSize: Int, delay: Duration): Behavior[MasterCommand] = ???
}
