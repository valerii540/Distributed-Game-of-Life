package vbosiak.master.actors

import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.typed._
import akka.util.Timeout
import vbosiak.common.models._
import vbosiak.master.actors.Master.MasterCommand
import vbosiak.master.controllers.models.{ClusterStatus, ClusterStatusResponse, WorkerResponse}
import vbosiak.master.models.{Mode, UserParameters}
import vbosiak.worker.actors.Worker
import vbosiak.worker.actors.Worker.{Reset, TellCapabilities, WorkerCommand, workerServiceKey}

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

  case object ClusterNotReady                                                                       extends MasterCommand
  final case class PrepareSimulation(replyTo: ActorRef[ControllerResponse], params: UserParameters) extends MasterCommand
  final case class TellClusterStatus(replyTo: ActorRef[ClusterStatusResponse])                      extends MasterCommand
  final case class ManualTrigger(replyTo: ActorRef[ControllerResponse])                             extends MasterCommand

  case object ShowWorkersFields extends MasterCommand

  private final case class WorkerIsReady(workerRef: ActorRef[WorkerCommand], capabilities: Capabilities) extends MasterCommand
  private final case class NextIteration()                                                               extends MasterCommand
  private final case class IterationDone(results: List[WorkerIterationResult], duration: FiniteDuration) extends MasterCommand
  private final case class ListingResponse(listing: Receptionist.Listing)                                extends MasterCommand
  private case object PreparationDone                                                                    extends MasterCommand

  def apply(cluster: Cluster): Behavior[MasterCommand] =
    Behaviors.setup { context =>
      context.log.info("Hello, I'm master {} at {}", context.self, cluster.selfMember.address)

      val listingResponseAdapter = context.messageAdapter[Receptionist.Listing](ListingResponse)

      context.system.receptionist ! Receptionist.Subscribe(workerServiceKey, listingResponseAdapter)

      new Master(context).setupLifeCycle()
    }
}

final class Master(context: ActorContext[MasterCommand]) {
  import Master._
  import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}

  private[this] implicit val ec: ExecutionContext         = context.executionContext
  private[this] implicit val system: ActorSystem[Nothing] = context.system

  def setupLifeCycle(
      workers: Map[ActorRef[WorkerCommand], Capabilities] = Map.empty
  ): Behavior[MasterCommand] =
    Behaviors.setup { context =>
      implicit val askTimeout: Timeout = 10.seconds

      Behaviors.receiveMessage {
        case ListingResponse(workerServiceKey.Listing(discovered)) =>
          val knownWorkers = workers.keys.toSet
          val newWorkers   = discovered -- knownWorkers
          val leftWorkers  = knownWorkers -- discovered

          if (newWorkers.nonEmpty) {
            context.log.info("[Cluster changes] Discovered {} new workers in the cluster: {}", newWorkers.size, newWorkers.mkString(", "))
            newWorkers.foreach { workerRef =>
              context.ask(workerRef, TellCapabilities) {
                case Success(capabilities) =>
                  context.log.debug("Capabilities of {} has been received: {}", workerRef, capabilities)
                  WorkerIsReady(workerRef, capabilities)
                case Failure(exception)    => throw exception
              }
            }
            Behaviors.same
          } else if (leftWorkers.nonEmpty) {
            context.log.warn("[Cluster changes] {} worker(s) have left the cluster: ", leftWorkers.size, leftWorkers.mkString(", "))
            setupLifeCycle(workers -- leftWorkers)
          } else
            Behaviors.same

        case WorkerIsReady(workerRef, capabilities) =>
          setupLifeCycle(workers + (workerRef -> capabilities))

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
            workersRaw = Some(workers.keys.toList)
          )

          Behaviors.same

        case ClusterNotReady => setupLifeCycle(Map.empty)

        case wrong =>
          context.log.error("Received {} in setup behaviour", wrong)
          Behaviors.same
      }
    }

  private[this] def prepareSimulation(workers: Map[ActorRef[WorkerCommand], Capabilities], params: UserParameters): Behavior[MasterCommand] = {
    implicit val workerPreparationTimeout: Timeout = 1.minute
    val workerVector                               = workers.keys.toVector

    val workersRep = workerVector.zipWithIndex.map { case (w, i) =>
      val leftNeighbor  = if (workerVector.isDefinedAt(i - 1)) workerVector(i - 1) else workerVector.last
      val rightNeighbor = if (workerVector.isDefinedAt(i + 1)) workerVector(i + 1) else workerVector.head

      WorkerRep(UUID.randomUUID(), w, Neighbors(leftNeighbor, rightNeighbor), workers(w))
    }

    val weakestWorker = workersRep.minBy(_.capabilities.maxFiledSideSize)
    context.log.info(
      "Collected all info about workers. Weakest worker can handle only {}x{} field ({}GB)",
      weakestWorker.capabilities.maxFiledSideSize,
      weakestWorker.capabilities.maxFiledSideSize,
      weakestWorker.capabilities.availableMemory / (1024 * 1024 * 1024)
    )

    Future
      .traverse(workersRep) { worker =>
        worker.actor.ask(Worker.NewSimulation(_, weakestWorker.capabilities.maxFiledSideSize, params.lifeFactor, worker.neighbors))
      }.onComplete {
        case Success(_)         => context.self ! PreparationDone
        case Failure(exception) => throw exception
      }

    params match {
      case UserParameters(Mode.Fastest, _, _)       =>
        fastestModeBehaviour(workersRep.toList, State(0), weakestWorker.capabilities.maxFiledSideSize)
      case UserParameters(Mode.Manual, _, _)        =>
        manualModeBehaviour(workersRep.toList, State(0), weakestWorker.capabilities.maxFiledSideSize, busy = false)
      case UserParameters(Mode.SoftTimed, delay, _) =>
        softTimedBehaviour(workersRep.toList, State(0), weakestWorker.capabilities.maxFiledSideSize, Duration(delay.get, TimeUnit.SECONDS))
    }
  }

  private[this] def fastestModeBehaviour(workers: List[WorkerRep], state: State, fieldSize: Int): Behavior[MasterCommand] =
    Behaviors.receiveMessage {
      case PreparationDone =>
        context.self ! NextIteration()
        Behaviors.same

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
          mode = Some(Mode.Fastest),
          iteration = Some(state.iteration),
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

      case ListingResponse(workerServiceKey.Listing(discovered)) =>
        handleClusterChanges(workers, discovered)

      case WorkerIsReady(_, _) =>
        context.log.warn("Cluster members cannot be changed during simulation. Ignoring new members")
        Behaviors.same

      case ManualTrigger(_) =>
        context.log.info("Manual simulation control is only supported in manual mode")
        Behaviors.same

      case wrong =>
        context.log.error("Received {} in fastestMode behaviour", wrong)
        Behaviors.same
    }

  private[this] def manualModeBehaviour(workers: List[WorkerRep], state: State, fieldSize: Int, busy: Boolean): Behavior[MasterCommand] =
    Behaviors.receiveMessage {
      case PreparationDone =>
        context.log.info("Field generated. Cluster is ready to receive next iteration command")
        Behaviors.same

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
          mode = Some(Mode.Manual),
          iteration = Some(state.iteration),
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

      case ListingResponse(workerServiceKey.Listing(discovered)) =>
        handleClusterChanges(workers, discovered)

      case WorkerIsReady(_, _) =>
        context.log.warn("Cluster members cannot be changed during simulation. Ignoring new members")
        Behaviors.same

      case wrong =>
        context.log.error("Received {} is manualMode behaviour", wrong)
        Behaviors.same
    }

  private[this] def softTimedBehaviour(workers: List[WorkerRep], state: State, fieldSize: Int, delay: Duration): Behavior[MasterCommand] = ???

  private[this] def handleClusterChanges(workers: List[WorkerRep], actual: Set[ActorRef[WorkerCommand]]): Behavior[MasterCommand] = {
    val knownWorkers = workers.map(_.actor).toSet
    val newWorkers   = actual -- knownWorkers
    val leftWorkers  = knownWorkers -- actual

    if (newWorkers.nonEmpty) {
      context.log.warn(
        "[Cluster changes] Discovered {} new worker(s) during simulation. Ignoring: {}",
        newWorkers.size,
        newWorkers.mkString(", ")
      )

      Behaviors.same
    } else if (leftWorkers.nonEmpty) {
      context.log.warn("[Cluster changes] {} worker(s) have left the cluster during simulation. Resetting cluster", leftWorkers.size)
      actual.foreach(_ ! Reset)
      setupLifeCycle(actual.map(w => w -> workers.find(_.actor == w).get.capabilities).toMap)
    } else
      Behaviors.same
  }

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
