package vbosiak.master.helpers

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.ActorContext
import akka.util.Timeout
import org.slf4j.LoggerFactory
import vbosiak.common.models.{Neighbors, WorkerRep}
import vbosiak.common.utils.Clock
import vbosiak.master.actors.Master.{MasterCommand, PreparationDone}
import vbosiak.master.models.{Size, UserParameters}
import vbosiak.worker.actors.Worker

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait MasterHelper {
  import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}

  implicit val context: ActorContext[MasterCommand]
  private implicit val system: ActorSystem[Nothing] = context.system

  def findStandAloneCandidate(size: Size, workers: Set[WorkerRep]): Option[WorkerRep] =
    workers.find(_.capabilities.availableMemory >= size.area)

  def divideUniverseBetweenWorkers(size: Size, workers: Set[WorkerRep]): Set[(WorkerRep, Size)] = {
    val workersQueue  = workers.to(mutable.Queue).sortBy(-_.capabilities.availableMemory)
    val chosenWorkers = {
      val queue = mutable.Queue.empty[(WorkerRep, Size)]

      var neededCounter = size.area
      while (neededCounter > 0) {
        val next       = workersQueue.dequeue()
        val width      = next.capabilities.availableMemory / size.height
        val workerPart = Size(size.height, (if (width > neededCounter) neededCounter else width).toInt)

        neededCounter -= workerPart.area
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
}
