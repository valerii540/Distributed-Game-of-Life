package vbosiak.master.actors

import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.typed._
import akka.util.Timeout
import vbosiak.common.models._
import vbosiak.common.utils.Clock
import vbosiak.master.actors.Master.MasterCommand
import vbosiak.master.controllers.models.{ClusterStatus, ClusterStatusResponse, WorkerResponse}
import vbosiak.master.models.{Mode, PreferredSize, UserParameters}
import vbosiak.worker.actors.Worker
import vbosiak.worker.actors.Worker._

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

  final case class PrepareSimulation(replyTo: ActorRef[ControllerResponse], params: UserParameters) extends MasterCommand
  final case class TellClusterStatus(replyTo: ActorRef[ClusterStatusResponse])                      extends MasterCommand
  final case class ManualTrigger(replyTo: ActorRef[ControllerResponse])                             extends MasterCommand
  case object ResetSimulation                                                                       extends MasterCommand

  case object ShowWorkersFields extends MasterCommand

  private final case class WorkerIsReady(workerRep: WorkerRep)                                          extends MasterCommand
  private final case class NextIteration()                                                              extends MasterCommand
  private final case class IterationDone(results: Set[WorkerIterationResult], duration: FiniteDuration) extends MasterCommand
  private final case class ListingResponse(listing: Receptionist.Listing)                               extends MasterCommand
  private final case class PreparationDone(population: Long, duration: FiniteDuration)                  extends MasterCommand

  def apply(cluster: Cluster): Behavior[MasterCommand] =
    Behaviors.setup { context =>
      context.log.info("Hello, I'm master {} at {}", context.self, cluster.selfMember.address)

      val listingResponseAdapter = context.messageAdapter[Receptionist.Listing](ListingResponse)

      context.system.receptionist ! Receptionist.Subscribe(workerServiceKey, listingResponseAdapter)

      new Master(context).setupLifeCycle(Set.empty)
    }
}

final class Master(context: ActorContext[MasterCommand]) {
  import Master._
  import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}

  private[this] implicit val ec: ExecutionContext         = context.executionContext
  private[this] implicit val system: ActorSystem[Nothing] = context.system

  def setupLifeCycle(
      workers: Set[WorkerRep]
  ): Behavior[MasterCommand] =
    Behaviors.setup { context =>
      implicit val askTimeout: Timeout = 10.seconds

      Behaviors.receiveMessage {
        case ListingResponse(workerServiceKey.Listing(discovered)) =>
          val knownWorkers = workers.map(_.actor)
          val newWorkers   = {
            val newConnected = discovered -- knownWorkers
            val duplicates   = newConnected.map(w => w -> w.path.name).filter(p => knownWorkers.map(_.path.name)(p._2))

            duplicates.foreach { case (ref, name) =>
              ref ! Die(s"Worker with ID $name already in cluster. You should die")
            }
            newConnected -- duplicates.map(_._1)
          }
          val leftWorkers  = knownWorkers -- discovered

          if (newWorkers.nonEmpty) {
            context.log.info(
              "[Cluster changes] Discovered {} new workers in the cluster: {}",
              newWorkers.size,
              newWorkers.map(_.path.name).mkString(", ")
            )

            newWorkers.foreach { workerRef =>
              context.ask(workerRef, TellAboutYou) {
                case Success(capabilities) =>
                  context.log.debug("Capabilities of {} has been received: {}", workerRef.path.name, capabilities)
                  WorkerIsReady(WorkerRep(workerRef, capabilities))
                case Failure(exception)    => throw exception
              }
            }
            Behaviors.same
          } else if (leftWorkers.nonEmpty) {
            context.log.warn(
              "[Cluster changes] {} worker(s) have left the cluster: {}",
              leftWorkers.size,
              leftWorkers.map(_.path.name).mkString(", ")
            )
            setupLifeCycle(workers.filterNot(w => leftWorkers(w.actor)))
          } else
            Behaviors.same

        case WorkerIsReady(workerRep) =>
          setupLifeCycle(workers + workerRep)

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
            workersRaw = Some(workers)
          )

          Behaviors.same

        case ShowWorkersFields =>
          context.log.warn("Cluster in Idle mode. Workers are empty")
          Behaviors.same

        case wrong =>
          context.log.error("Received {} in setup behaviour", wrong)
          Behaviors.same
      }
    }

  private[this] def prepareSimulation(workers: Set[WorkerRep], params: UserParameters): Behavior[MasterCommand] = {
    implicit val workerPreparationTimeout: Timeout = 1.minute
//    val workerVector                               = workers.keys.toVector

//    val workersRep = workerVector.zipWithIndex.map { case (w, i) =>
//      val leftNeighbor  = if (workerVector.isDefinedAt(i - 1)) workerVector(i - 1) else workerVector.last
//      val rightNeighbor = if (workerVector.isDefinedAt(i + 1)) workerVector(i + 1) else workerVector.head
//
//      WorkerRep(UUID.randomUUID(), w, Neighbors(leftNeighbor, rightNeighbor), workers(w))
//    }

    (params.preferredFieldSize, params.forceDistribution) match {
      case (Some(PreferredSize(height, width)), true)  => ???
      case (Some(PreferredSize(height, width)), false) =>
        val needed    = height * width.toLong
        val chosenOne = workers.find(_.capabilities.availableMemory > needed)
        if (chosenOne.isDefined) {
          context.log.info("Found worker capable for stand-alone processing: {}", chosenOne.get.actor.path.name)

          val startedAt = System.nanoTime()
          context.ask(chosenOne.get.actor, Worker.NewSimulation(_, (height, width), params.lifeFactor, None)) {
            case Success(population) => PreparationDone(population, Clock.fromNano(System.nanoTime() - startedAt))
            case Failure(exception)  =>
              context.log.error("Simulation start failure:", exception)
              throw exception
          }

          params.mode match {
            case Mode.Manual    =>
              manualModeBehaviour(Set(chosenOne.get), workers - chosenOne.get, State(0), busy = false)
            case Mode.Fastest   =>
              fastestModeBehaviour(Set(chosenOne.get), workers - chosenOne.get, State(0))
            case Mode.SoftTimed =>
              softTimedBehaviour(Set(chosenOne.get), workers - chosenOne.get, State(0), FiniteDuration(params.delay.get, TimeUnit.SECONDS))
          }
        } else
          ???

      case (None, true)  => ???
      case (None, false) => ???
    }

//    val weakestWorker = workersRep.minBy(_.capabilities.maxFiledSideSize)
//    context.log.info(
//      "Collected all info about workers. Weakest worker can handle only {}x{} field ({}GB)",
//      weakestWorker.capabilities.maxFiledSideSize,
//      weakestWorker.capabilities.maxFiledSideSize,
//      weakestWorker.capabilities.availableMemory / (1024 * 1024 * 1024)
//    )
//
//    Future
//      .traverse(workersRep) { worker =>
//        worker.actor.ask(Worker.NewSimulation(_, weakestWorker.capabilities.maxFiledSideSize, params.lifeFactor, worker.neighbors))
//      }.onComplete {
//        case Success(_)         => context.self ! PreparationDone
//        case Failure(exception) => throw exception
//      }
  }

  private[this] def fastestModeBehaviour(active: Set[WorkerRep], inactive: Set[WorkerRep], state: State): Behavior[MasterCommand] =
    Behaviors.receiveMessage {
      case PreparationDone(population, duration) =>
        context.log.info(
          "Field generated in {}s with initial population {}",
          duration.toMillis / 1000f,
          population
        )
        context.self ! NextIteration()
        Behaviors.same

      case NextIteration() =>
        nextIteration(active)

        fastestModeBehaviour(active, inactive, state.copy(iteration = state.iteration + 1))

      case IterationDone(results, duration) =>
        iterationDoneLog(results, state, duration)

        nextIteration(active)

        fastestModeBehaviour(active, inactive, state.copy(iteration = state.iteration + 1))

      case TellClusterStatus(replyTo) =>
        tellClusterStatus(replyTo, active, inactive, Mode.Fastest, state)

      case PrepareSimulation(replyTo, _) =>
        replyTo ! AlreadyRunning
        Behaviors.same

      case ListingResponse(workerServiceKey.Listing(discovered)) =>
        handleClusterChanges(active, inactive, discovered, state, Mode.Fastest)

      case WorkerIsReady(_) =>
        context.log.warn("Cluster members cannot be changed during simulation. Ignoring new members")
        Behaviors.same

      case ManualTrigger(_) =>
        context.log.info("Manual simulation control is only supported in manual mode")
        Behaviors.same

      case ResetSimulation =>
        (active ++ inactive).foreach(_.actor ! Reset)
        setupLifeCycle(active ++ inactive)

      case ShowWorkersFields =>
        active.foreach(_.actor ! ShowYourField)
        Behaviors.same

      case wrong =>
        context.log.error("Received {} in fastestMode behaviour", wrong)
        Behaviors.same
    }

  private[this] def manualModeBehaviour(
      active: Set[WorkerRep],
      inactive: Set[WorkerRep],
      state: State,
      busy: Boolean
  ): Behavior[MasterCommand] =
    Behaviors.receiveMessage {
      case PreparationDone(population, duration) =>
        context.log.info(
          "Field generated in {}s with initial population {}. Cluster is ready to receive next iteration command",
          duration.toMillis / 1000f,
          population
        )
        Behaviors.same

      case ManualTrigger(replyTo) =>
        if (busy) {
          replyTo ! AlreadyRunning
          Behaviors.same
        } else {
          context.log.info("Triggering iteration #{}", state.iteration + 1)
          nextIteration(active)

          replyTo ! OK

          manualModeBehaviour(active, inactive, state.copy(iteration = state.iteration + 1), busy = true)
        }

      case IterationDone(results, duration) =>
        iterationDoneLog(results, state, duration)

        manualModeBehaviour(active, inactive, state, busy = false)

      case TellClusterStatus(replyTo) =>
        tellClusterStatus(replyTo, active, inactive, Mode.Manual, state)

      case PrepareSimulation(replyTo, _) =>
        replyTo ! AlreadyRunning
        Behaviors.same

      case ListingResponse(workerServiceKey.Listing(discovered)) =>
        handleClusterChanges(active, inactive, discovered, state, Mode.Manual, Some(busy))

      case WorkerIsReady(_) =>
        context.log.warn("Cluster members cannot be changed during simulation. Ignoring new members")
        Behaviors.same

      case ResetSimulation =>
        (active ++ inactive).foreach(_.actor ! Reset)
        setupLifeCycle(active ++ inactive)

      case ShowWorkersFields =>
        active.foreach(_.actor ! ShowYourField)
        Behaviors.same

      case wrong =>
        context.log.error("Received {} is manualMode behaviour", wrong)
        Behaviors.same
    }

  private[this] def softTimedBehaviour(
      active: Set[WorkerRep],
      inactive: Set[WorkerRep],
      state: State,
      delay: FiniteDuration
  ): Behavior[MasterCommand] = ???

  private[this] def handleClusterChanges(
      active: Set[WorkerRep],
      inactive: Set[WorkerRep],
      actual: Set[ActorRef[WorkerCommand]],
      state: State,
      mode: Mode,
      busy: Option[Boolean] = None,
      delay: Option[FiniteDuration] = None
  ): Behavior[MasterCommand] = {
    val activeWorkers       = active.map(_.actor)
    val inactiveWorkers     = inactive.map(_.actor)
    val newWorkers          = actual -- (activeWorkers ++ inactiveWorkers)
    val lostActiveWorkers   = activeWorkers -- actual
    val lostInactiveWorkers = inactiveWorkers -- actual

    if (newWorkers.nonEmpty) {
      context.log.warn(
        "[Cluster changes] Discovered {} new worker(s) during simulation. Ignoring: {}",
        newWorkers.size,
        newWorkers.mkString(", ")
      )
      Behaviors.same
    } else if (lostActiveWorkers.nonEmpty) {
      context.log.warn("[Cluster changes] {} active worker(s) have left the cluster during simulation. Resetting cluster", lostActiveWorkers.size)
      actual.foreach(_ ! Reset)
      setupLifeCycle(
        actual.map { a =>
          WorkerRep(
            actor = a,
            capabilities = (active ++ inactive).find(_.actor == a).get.capabilities
          )
        }
      )
    } else if (lostInactiveWorkers.nonEmpty) {
      context.log.info("[Cluster changes] {} inactive worker(s) left the cluster during simulation. Continue", lostInactiveWorkers.size)
      mode match {
        case Mode.Manual    => manualModeBehaviour(active, inactive.filterNot(w => lostInactiveWorkers(w.actor)), state, busy.get)
        case Mode.Fastest   => fastestModeBehaviour(active, inactive.filterNot(w => lostInactiveWorkers(w.actor)), state)
        case Mode.SoftTimed => softTimedBehaviour(active, inactive.filterNot(w => lostInactiveWorkers(w.actor)), state, delay.get)
      }
    } else {
      context.log.warn(
        "Something wrong with handleClusterChanges:\n{}\n{}\n{}\n{}\n{}",
        activeWorkers,
        inactiveWorkers,
        newWorkers,
        lostActiveWorkers,
        lostInactiveWorkers
      )
      Behaviors.same
    }
  }

  private[this] def nextIteration(workers: Set[WorkerRep]): Unit = {
    implicit val timeout: Timeout = 1.minute
    val startedAt                 = System.nanoTime()
    Future
      .traverse(workers)(_.actor.ask(Worker.NextIteration))
      .onComplete {
        case Success(stats)     => context.self ! IterationDone(stats, Duration(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS))
        case Failure(exception) => throw exception
      }
  }

  private[this] def iterationDoneLog(results: Set[WorkerIterationResult], state: State, duration: FiniteDuration): Unit = {
    context.log.info(
      "Iteration #{} completed at {}s with {} population remaining",
      state.iteration,
      duration.toMillis / 1000f,
      results.foldLeft(0L)((acc, res) => acc + res.stats.population)
    )
    results.foreach { result =>
      context.log.debug(
        "Worker {} completed iteration #{} at {}s with {} population remaining",
        result.ref.path.name,
        state.iteration,
        result.stats.duration.toMillis / 1000f,
        result.stats.population
      )
    }
  }

  private[this] def tellClusterStatus(
      replyTo: ActorRef[ClusterStatusResponse],
      workers: Set[WorkerRep],
      inactiveWorkers: Set[WorkerRep],
      mode: Mode,
      state: State
  ): Behavior[MasterCommand] = {
    val workersResponse = workers.map { rep =>
      val neighbors =
        if (rep.neighbors.isDefined)
          List(
            workers.find(_.actor == rep.neighbors.get.left).get.actor.path.name,
            workers.find(_.actor == rep.neighbors.get.right).get.actor.path.name
          )
        else Nil

      WorkerResponse(
        ref = rep.actor,
        neighbors = neighbors,
        capabilities = rep.capabilities,
        active = true
      )
    } ++ inactiveWorkers.map { rep =>
      WorkerResponse(
        ref = rep.actor,
        neighbors = Nil,
        capabilities = rep.capabilities,
        active = false
      )
    }

    replyTo ! ClusterStatusResponse(
      status = ClusterStatus.Running,
      mode = Some(mode),
      iteration = Some(state.iteration),
      workers = Some(workersResponse)
    )

    Behaviors.same
  }
}
