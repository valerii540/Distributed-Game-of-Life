package vbosiak.master.actors

import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.typed._
import akka.util.Timeout
import vbosiak.common.models._
import vbosiak.master.actors.Master.MasterCommand
import vbosiak.master.controllers.models.{ClusterStatus, ClusterStatusResponse, WorkerResponse}
import vbosiak.master.helpers.MasterHelper
import vbosiak.master.models.{Mode, Size, UserParameters}
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
  case object OK                                       extends ControllerResponse
  case object AlreadyRunning                           extends ControllerResponse
  case object NoWorkersInCluster                       extends ControllerResponse
  final case class ImpossibleToProcess(reason: String) extends ControllerResponse

  /** Actor commands */
  sealed trait MasterCommand extends CborSerializable

  final case class PrepareSimulation(replyTo: ActorRef[ControllerResponse], params: UserParameters) extends MasterCommand
  final case class TellClusterStatus(replyTo: ActorRef[ClusterStatusResponse])                      extends MasterCommand
  final case class ManualTrigger(replyTo: ActorRef[ControllerResponse])                             extends MasterCommand
  case object ResetSimulation                                                                       extends MasterCommand
  case object ClusterSelfTest                                                                       extends MasterCommand

  case object ShowWorkersFields extends MasterCommand

  private[master] final case class WorkerIsReady(workerRep: WorkerRep)                                          extends MasterCommand
  private[master] final case class NextIteration()                                                              extends MasterCommand
  private[master] final case class IterationDone(results: Set[WorkerIterationResult], duration: FiniteDuration) extends MasterCommand
  private[master] final case class ListingResponse(listing: Receptionist.Listing)                               extends MasterCommand
  private[master] final case class PreparationDone(population: Long, duration: FiniteDuration)                  extends MasterCommand

  def apply(cluster: Cluster): Behavior[MasterCommand] =
    Behaviors.setup { context =>
      context.log.info("Hello, I'm master {} at {}", context.self, cluster.selfMember.address)

      val listingResponseAdapter = context.messageAdapter[Receptionist.Listing](ListingResponse)

      context.system.receptionist ! Receptionist.Subscribe(workerServiceKey, listingResponseAdapter)

      new Master()(context).setupLifeCycle(Set.empty)
    }
}

final class Master()(override implicit val context: ActorContext[MasterCommand]) extends MasterHelper {
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

            duplicates.foreach { case (ref, id) =>
              ref ! Die(s"Worker with ID $id already in cluster. You should die")
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
          } else
            params.preferredFieldSize match {
              case Some(size) =>
                val neededResources    = size.area
                val availableResources = workers.foldLeft(0L)((a, w) => a + w.capabilities.availableMemory)
                if (availableResources < neededResources) {
                  controller ! ImpossibleToProcess(
                    s"Requested field size too big for current cluster. Requested bytes: $neededResources, available: $availableResources"
                  )
                  Behaviors.same
                } else {
                  controller ! OK
                  prepareSimulation(workers, params)
                }
              case None       =>
                controller ! OK
                prepareSimulation(workers, params)
            }

        case TellClusterStatus(replyTo) =>
          replyTo ! ClusterStatusResponse(
            status = ClusterStatus.Idle,
            workersRaw = Some(workers)
          )

          Behaviors.same

        case ClusterSelfTest =>
          if (workers.size < 2) {
            context.log.warn("[Self-test] Unable to perform cluster self-test. Self-test requires two or more workers")
            Behaviors.same
          } else {
            val limitedWorkers = workers.map(w => w.copy(capabilities = w.capabilities.copy(availableMemory = 100)))

            val chosenOnes = divideUniverseBetweenWorkers(Size(10, 20), limitedWorkers)

            context.log.info("[Self-test] Chosen two workers for self-test: {}", chosenOnes.map(_._1.actor.path.name))
            askForSelfTest(chosenOnes)
            Behaviors.same
          }

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

    val activeWorkers: Set[WorkerRep] =
      (params.preferredFieldSize, params.forceDistribution) match {
        case (Some(size), true)  => ???
        case (Some(size), false) =>
          val chosenOnes = findStandAloneCandidate(size, workers)
            .map(w => Set(w -> size))
            .getOrElse(divideUniverseBetweenWorkers(size, workers))

          context.log.info(
            "{} worker(s) have been chosen to create a new divine {} universe: {}",
            chosenOnes.size,
            size.pretty,
            chosenOnes.map(_._1.actor.path.name).mkString(", ")
          )
          context.log.debug("Chosen workers: {}", chosenOnes.map(ws => s"${ws._1.actor.path.name}: ${ws._2.pretty}").mkString(", "))

          askForNewSimulation(chosenOnes, params)
          chosenOnes.map(_._1)

        case (None, true)  => ???
        case (None, false) => ???
      }

    params.mode match {
      case Mode.Manual    =>
        manualModeBehaviour(activeWorkers, workers -- activeWorkers, State(0), busy = false)
      case Mode.Fastest   =>
        fastestModeBehaviour(activeWorkers, workers -- activeWorkers, State(0))
      case Mode.SoftTimed =>
        context.log.error("Soft-timed mode not implemented yet")
        Behaviors.same
    }
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
        context.log.info("Received Reset command. Resetting cluster...")
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
        context.log.info("Received Reset command. Resetting cluster...")
        (active ++ inactive).foreach(_.actor ! Reset)
        setupLifeCycle(active ++ inactive)

      case ShowWorkersFields =>
        active.foreach(_.actor ! ShowYourField)
        Behaviors.same

      case wrong =>
        context.log.error("Received {} is manualMode behaviour", wrong)
        Behaviors.same
    }

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
        case Mode.SoftTimed => Behaviors.same
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
