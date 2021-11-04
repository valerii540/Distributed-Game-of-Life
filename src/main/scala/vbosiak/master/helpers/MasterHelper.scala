package vbosiak.master.helpers

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.util.Timeout
import org.slf4j.LoggerFactory
import vbosiak.common.models.{Neighbors, WorkerIterationResult, WorkerRep}
import vbosiak.common.utils.Clock
import vbosiak.master.actors.Master.{IterationDone, MasterCommand, PreparationDone, State}
import vbosiak.master.controllers.models.{ClusterStatus, ClusterStatusResponse, WorkerResponse}
import vbosiak.master.models.{Mode, Size, UserParameters}
import vbosiak.worker.actors.Worker

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait MasterHelper {
  import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}

  implicit val context: ActorContext[MasterCommand]
  implicit val ec: ExecutionContext
  implicit val system: ActorSystem[Nothing]

  def findStandAloneCandidate(size: Size, workers: Set[WorkerRep]): Option[WorkerRep] =
    workers.toSeq.sortBy(_.capabilities.availableMemory).find(_.capabilities.availableMemory >= size.area)

  def divideUniverseBetweenWorkers(size: Size, workers: Set[WorkerRep]): Set[(WorkerRep, Size)] = {
    val workersQueue  = workers.to(mutable.Queue).sortBy(-_.capabilities.availableMemory)
    val chosenWorkers = {
      val queue = mutable.Queue.empty[(WorkerRep, Size)]

      var widthCounter: Long = size.width
      while (widthCounter > 0) {
        val next       = workersQueue.dequeue()
        val maxWidth   = next.capabilities.availableMemory / size.height
        val workerPart =
          if (widthCounter - maxWidth > 0) {
            widthCounter -= maxWidth
            Size(size.height, maxWidth.toInt)
          } else {
            val s = Size(size.height, widthCounter.toInt)
            widthCounter = 0
            s
          }
        queue += next -> workerPart

      }
      queue.toVector
    }

    val withNeighbors = chosenWorkers.zipWithIndex.map { case ((worker, size), i) =>
      val left  = if (chosenWorkers.isDefinedAt(i - 1)) chosenWorkers(i - 1)._1.actor else chosenWorkers.last._1.actor
      val right = if (chosenWorkers.isDefinedAt(i + 1)) chosenWorkers(i + 1)._1.actor else chosenWorkers.head._1.actor

      worker.copy(neighbors = Some(Neighbors(left, right))) -> size
    }

    withNeighbors.toSet
  }

  def askForNewSimulation(workers: Set[(WorkerRep, Size)], params: UserParameters)(implicit ec: ExecutionContext, timeout: Timeout): Unit = {
    val startedAt = System.nanoTime()
    Future
      .traverse(workers.zipWithIndex) { case ((worker, size), i) =>
        worker.actor.ask(Worker.NewSimulation(_, size, params.lifeFactor, worker.neighbors, params.seed.map(s => s + i)))
      }.onComplete {
        case Success(populations) =>
          context.self ! PreparationDone(populations.foldLeft(0L)((a, p) => a + p), Clock.fromNano(System.nanoTime() - startedAt))
        case Failure(exception)   => throw exception
      }
  }

  def nextIteration(workers: Set[WorkerRep], next: Int): Unit = {
    implicit val timeout: Timeout = 10.minute
    val startedAt                 = System.nanoTime()
    Future
      .traverse(workers)(_.actor.ask(Worker.NextIteration(_, next)))
      .onComplete {
        case Success(stats)     => context.self ! IterationDone(stats, Duration(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS))
        case Failure(exception) => throw exception
      }
  }

  def iterationDoneLog(results: Set[WorkerIterationResult], state: State, duration: FiniteDuration): Unit = {
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

  def askForSelfTest(workers: Set[(WorkerRep, Size)])(implicit ec: ExecutionContext, timeout: Timeout): Unit = {
    val logger = LoggerFactory.getLogger(classOf[MasterHelper])
    Future
      .traverse(workers) { case (worker, _) =>
        worker.actor.ask(Worker.PrepareSelfTest(_, worker.neighbors.get))
      }.onComplete {
        case Success(_)         =>
          Future
            .traverse(workers) { case (worker, _) =>
              worker.actor.ask(Worker.NextIteration(_, 0)).transform {
                case Success(stats) =>
                  if (stats.stats.population != 20)
                    logger.warn("[Self-test] Bad population check - {} from {}", stats.stats.population, worker.actor.path.name)
                  else
                    logger.info("[Self-test] {} - population OK", worker.actor.path.name)
                  Success(())
                case failure        => failure
              }
            }.onComplete {
              case Success(_)         =>
                workers.foreach(w => w._1.actor ! Worker.ShowYourField)
                workers.foreach(w => w._1.actor ! Worker.Reset)
                logger.info("[Self-test] Completed")
              case Failure(exception) => throw exception
            }
        case Failure(exception) => throw exception
      }
  }

  def tellClusterStatus(
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
