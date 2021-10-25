package vbosiak.master.actors

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.typed._
import akka.util.Timeout
import vbosiak.common.models._
import vbosiak.master.actors.Master.MasterCommand
import vbosiak.master.controllers.models.{ClusterStatus, ClusterStatusResponse, WorkerResponse}
import vbosiak.master.models.{Mode, UserParameters}
import vbosiak.worker.actors.Worker
import vbosiak.worker.actors.Worker.{TellCapabilities, WorkerCommand}

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
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

  final case class WorkerIsReady(ref: ActorRef[WorkerCommand])                                      extends MasterCommand
  case object ClusterNotReady                                                                       extends MasterCommand
  final case class PrepareSimulation(replyTo: ActorRef[ControllerResponse], params: UserParameters) extends MasterCommand
  final case class TellClusterStatus(replyTo: ActorRef[ClusterStatusResponse])                      extends MasterCommand
  final case class ManualTrigger(replyTo: ActorRef[ControllerResponse])                             extends MasterCommand

  private final case class WorkerIsReadyInternal(workerRef: ActorRef[WorkerCommand], capabilities: Capabilities) extends MasterCommand
  private final case class NextIteration()                                                                       extends MasterCommand
  private final case class IterationDone(results: List[WorkerIterationResult], duration: FiniteDuration)         extends MasterCommand

  def apply(cluster: Cluster): Behavior[MasterCommand] =
    Behaviors.setup { context =>
      context.log.info("Hello, I'm master {} at {}", context.self, cluster.selfMember.address)

      new Master(context).setupLifeCycle()
    }
}

final class Master(context: ActorContext[MasterCommand]) {
  import Master._
  import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}

  private[this] implicit val ec: ExecutionContext         = context.executionContext
  private[this] implicit val system: ActorSystem[Nothing] = context.system

  def setupLifeCycle(
      workers: Vector[(ActorRef[WorkerCommand], Capabilities)] = Vector.empty
  ): Behavior[MasterCommand] =
    Behaviors.setup { context =>
      implicit val askTimeout: Timeout = 10.seconds

      Behaviors.receiveMessage {
        case WorkerIsReady(workerRef) =>
          context.ask(workerRef, TellCapabilities) {
            case Success(capabilities) => WorkerIsReadyInternal(workerRef, capabilities)
            case Failure(exception)    => throw exception
          }
          Behaviors.same

        case WorkerIsReadyInternal(workerRef, capabilities) =>
          setupLifeCycle(workers :+ (workerRef, capabilities))

        case PrepareSimulation(controller, params) =>
          if (workers.isEmpty) {
            controller ! NoWorkersInCluster
            Behaviors.same
          } else {
            controller ! OK
            prepareSimulation(workers, params)
          }

        case TellClusterStatus(replyTo) =>
          replyTo ! ClusterStatusResponse(
            status = ClusterStatus.Idle,
            description = "Waiting for user action",
            workersRaw = Some(workers.map(_._1).toList)
          )

          Behaviors.same

        case ClusterNotReady => setupLifeCycle(Vector.empty)

        case wrong =>
          context.log.error("Received {} is setup behaviour", wrong)
          Behaviors.same
      }
    }

  private[this] def prepareSimulation(workers: Vector[(ActorRef[WorkerCommand], Capabilities)], params: UserParameters): Behavior[MasterCommand] = {
    implicit val timeout: Timeout = 5.seconds

    val workersRep = workers.zipWithIndex.map { case (w, i) =>
      val leftNeighbor  = if (workers.isDefinedAt(i - 1)) workers(i - 1)._1 else workers.last._1
      val rightNeighbor = if (workers.isDefinedAt(i + 1)) workers(i + 1)._1 else workers.head._1

      WorkerRep(UUID.randomUUID(), w._1, Neighbors(leftNeighbor, rightNeighbor), w._2)
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
        case Success(_)         => context.self ! NextIteration()
        case Failure(exception) => throw exception
      }

    params match {
      case UserParameters(Mode.Fastest, _)       =>
        fastestModeBehaviour(workersRep.toList, State(0), weakestWorker.capabilities.maxFiledSideSize)
      case UserParameters(Mode.Manual, _)        =>
        manualModeBehaviour(workersRep.toList, State(0), weakestWorker.capabilities.maxFiledSideSize, busy = false)
      case UserParameters(Mode.SoftTimed, delay) =>
        softTimedBehaviour(workersRep.toList, State(0), weakestWorker.capabilities.maxFiledSideSize, Duration(delay.get, TimeUnit.SECONDS))
    }
  }

  private[this] def fastestModeBehaviour(workers: List[WorkerRep], state: State, fieldSize: Int): Behavior[MasterCommand] =
    Behaviors.receiveMessage {
      case NextIteration() =>
        context.log.info("Starting simulation with {} workers and {}x{} squares size", workers.size, fieldSize, fieldSize)

        nextIteration(workers)

        fastestModeBehaviour(workers, state.copy(iteration = state.iteration + 1), fieldSize)

      case IterationDone(results, duration) =>
        iterationDoneLog(results, state, duration)

        nextIteration(workers)

        fastestModeBehaviour(workers, state.copy(iteration = state.iteration + 1), fieldSize)

      case TellClusterStatus(replyTo) =>
        replyTo ! ClusterStatusResponse(
          status = ClusterStatus.Running,
          description = s"Running in fastest mode. Iteration #${state.iteration}",
          workers = Some(
            workers.map(rep =>
              WorkerResponse(
                rep.id,
                rep.actor,
                List( //TODO: replace this comparison with something smarter
                  workers.find(_.actor.toString == rep.neighbors.left.toString).get.id,
                  workers.find(_.actor.toString == rep.neighbors.right.toString).get.id
                ),
                rep.capabilities
              )
            )
          )
        )
        Behaviors.same

      case ClusterNotReady =>
        context.log.error("Cluster consistency failure. Worker down. Resetting self to initial state")
        setupLifeCycle()

      case PrepareSimulation(replyTo, _) =>
        replyTo ! AlreadyRunning
        Behaviors.same

      case WorkerIsReady(_) | WorkerIsReadyInternal(_, _) =>
        context.log.warn("Cluster members cannot be changed during simulation. Ignoring new members")
        Behaviors.same

      case ManualTrigger(_) =>
        context.log.info("Manual simulation control is only supported in manual mode")
        Behaviors.same
    }

  private[this] def manualModeBehaviour(workers: List[WorkerRep], state: State, fieldSize: Int, busy: Boolean): Behavior[MasterCommand] =
    Behaviors.receiveMessage {
      case ManualTrigger(replyTo) =>
        if (busy) {
          replyTo ! AlreadyRunning
          Behaviors.same
        } else {
          context.log.info("Triggering iteration #{}", state.iteration)
          nextIteration(workers)

          manualModeBehaviour(workers, state.copy(iteration = state.iteration + 1), fieldSize, busy = true)
        }

      case IterationDone(results, duration) =>
        iterationDoneLog(results, state, duration)

        manualModeBehaviour(workers, state, fieldSize, busy = false)

      case TellClusterStatus(replyTo) =>
        replyTo ! ClusterStatusResponse(
          status = ClusterStatus.Running,
          description = s"Running in manual mode. Iteration #${state.iteration}",
          workers = Some(
            workers.map(rep =>
              WorkerResponse(
                rep.id,
                rep.actor,
                List( //TODO: replace this comparison with something smarter
                  workers.find(_.actor.toString == rep.neighbors.left.toString).get.id,
                  workers.find(_.actor.toString == rep.neighbors.right.toString).get.id
                ),
                rep.capabilities
              )
            )
          )
        )
        Behaviors.same

      case ClusterNotReady =>
        context.log.error("Cluster consistency failure. Worker down. Resetting self to initial state")
        setupLifeCycle()

      case PrepareSimulation(replyTo, _) =>
        replyTo ! AlreadyRunning
        Behaviors.same

      case WorkerIsReady(_) | WorkerIsReadyInternal(_, _) =>
        context.log.warn("Cluster members cannot be changed during simulation. Ignoring new members")
        Behaviors.same

      case wrong =>
        context.log.error("Received {} is manualMode behaviour", wrong)
        Behaviors.same
    }

  private[this] def softTimedBehaviour(workers: List[WorkerRep], state: State, fieldSize: Int, delay: Duration): Behavior[MasterCommand] = ???

  private[this] def nextIteration(workers: List[WorkerRep]): Unit = {
    implicit val timeout: Timeout = 1.minute
    val startedAt                 = System.nanoTime()
    Future
      .traverse(workers)(_.actor.ask(Worker.NextIteration))
      .onComplete {
        case Success(stats)     => context.self ! IterationDone(stats, Duration(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS))
        case Failure(exception) => throw exception
      }
  }

  private[this] def iterationDoneLog(results: List[WorkerIterationResult], state: State, duration: FiniteDuration): Unit = {
    context.log.info("Master iteration {} completed at {} seconds", state.iteration, duration.toSeconds)
    results.foreach { result =>
      context.log.info(
        "Worker {} completed iteration {} at {} seconds with {} population remaining",
        result.ref,
        state.iteration,
        result.stats.duration.toSeconds,
        result.stats.population
      )
    }
  }
}
