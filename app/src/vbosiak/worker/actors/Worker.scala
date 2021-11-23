package vbosiak.worker.actors

import akka.Done
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.Timeout
import vbosiak.common.models._
import vbosiak.common.utils.{Clock, ResourcesInspector}
import vbosiak.master.controllers.models.Size
import vbosiak.worker.actors.Worker.WorkerCommand
import vbosiak.worker.helpers.WorkerHelper
import vbosiak.worker.models.WorkerBehaviour

import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object Worker {
  type WorkerId       = String
  type Field          = ArraySeq[ArraySeq[Boolean]]
  type Side           = ArraySeq[Boolean]
  type NeighborsSides = (Option[Side], Option[Side])

  val workerServiceKey: ServiceKey[WorkerCommand] = ServiceKey("worker")

  sealed trait WorkerCommand extends CborSerializable

  case object Reset                                               extends WorkerCommand
  final case class Die(reason: String)                            extends WorkerCommand
  final case class TellAboutYou(replyTo: ActorRef[Capabilities])  extends WorkerCommand
  final case class TellStatus(replyTo: ActorRef[WorkerBehaviour]) extends WorkerCommand

  final case class NewSimulation(replyTo: ActorRef[Int], fieldSize: Size, lifeFactor: Float, neighbors: Option[Neighbors], seed: Option[Long])
      extends WorkerCommand
  final case class NextIteration(replyTo: ActorRef[WorkerIterationResult], next: Int) extends WorkerCommand
  final case class TellFieldLeftSide(replyTo: ActorRef[List[Boolean]])                extends WorkerCommand
  final case class TellFieldRightSide(replyTo: ActorRef[List[Boolean]])               extends WorkerCommand

  case object ShowYourField                                                       extends WorkerCommand
  final case class PrepareSelfTest(replyTo: ActorRef[Done], neighbors: Neighbors) extends WorkerCommand

  private final case class UpdateLeftSide(replyTo: ActorRef[WorkerIterationResult], side: Side, duration: FiniteDuration)     extends WorkerCommand
  private final case class UpdateRightSide(replyTo: ActorRef[WorkerIterationResult], side: Side, duration: FiniteDuration)    extends WorkerCommand
  private final case class IterationCompleted(replyTo: ActorRef[WorkerIterationResult], result: (Field, Int, FiniteDuration)) extends WorkerCommand
  private final case class AskingFailure(throwable: Throwable)                                                                extends WorkerCommand

  def apply(): Behavior[WorkerCommand] =
    Behaviors.setup { context =>
      context.log.info("Hello, I'm worker {}", context.self.path.name)

      context.system.receptionist ! Receptionist.Register(workerServiceKey, context.self)

      new Worker()(context).initialBehaviour()
    }
}

private final class Worker()(override implicit val context: ActorContext[WorkerCommand]) extends WorkerHelper {
  import Worker._

  private[this] implicit val ec: ExecutionContext = context.executionContext

  private def initialBehaviour(): Behavior[WorkerCommand] =
    Behaviors.receiveMessage {
      case TellStatus(replyTo) =>
        replyTo ! WorkerBehaviour.Idle
        Behaviors.same

      case TellAboutYou(replyTo) =>
        replyTo ! ResourcesInspector.processingCapabilities
        Behaviors.same

      case NewSimulation(replyTo, fieldSize, lifeFactor, neighbors, seed) =>
        context.log.info("[Init] Preparing new simulation for {} field with life factor {}", fieldSize.pretty, lifeFactor)
        seed.foreach(s => context.log.info("[Init] Initializing with seed: {}", s))

        val (field, duration) = Clock.withMeasuring {
          createNewField(fieldSize, lifeFactor, seed)
        }

        context.log.info("[Init] Initialized {}x{} field in {}s", field.size, field.head.size, duration.toMillis / 1000f)

        replyTo ! field.foldLeft(0)((acc, row) => acc + row.count(identity))

        if (neighbors.isEmpty) {
          context.log.info("[Init] Working in the stand-alone mode")
          singleWorkerSimulationBehaviour(field)
        } else {
          context.log.info("[Init] Working in the multi worker mode with {}", neighbors.get)
          multiWorkerSimulationBehaviour(neighbors.get, ArraySeq.empty, 0, (None, None), field)
        }

      case PrepareSelfTest(replyTo, neighbors) =>
        context.log.info("[Self-test] Preparing self-test with neighbors: {}", neighbors)
        replyTo ! Done
        multiWorkerSimulationBehaviour(neighbors, ArraySeq.empty, 0, (None, None), generateStableTestSample)

      case Die(reason) => handleDieCommand(reason)

      case wrong => handleWrongCommand(wrong, "initial")
    }

  private[this] def multiWorkerSimulationBehaviour(
      workerNeighbors: Neighbors,
      field: Field,
      iteration: Int,
      neighborsSides: NeighborsSides = (None, None),
      nextField: Field = ArraySeq.empty
  ): Behavior[WorkerCommand] =
    Behaviors.receiveMessage {
      case TellStatus(replyTo) =>
        replyTo ! WorkerBehaviour.Processing(standAlone = false)
        Behaviors.same

      case NextIteration(replyTo, next) =>
        context.log.info("[Iteration #{}] Received NextIteration command", next)
        implicit val timeout: Timeout = 30.seconds
        val statedAt                  = System.nanoTime()
        // Ask left worker about it's right side
        context.ask(workerNeighbors.left, TellFieldRightSide) {
          case Success(side)      => UpdateLeftSide(replyTo, side.to(ArraySeq), Clock.fromNano(System.nanoTime() - statedAt))
          case Failure(exception) => AskingFailure(exception)
        }

        // Ask right worker about it's left side
        context.ask(workerNeighbors.right, TellFieldLeftSide) {
          case Success(side)      => UpdateRightSide(replyTo, side.to(ArraySeq), Clock.fromNano(System.nanoTime() - statedAt))
          case Failure(exception) => AskingFailure(exception)
        }

        multiWorkerSimulationBehaviour(workerNeighbors, nextField, next)

      case UpdateLeftSide(replyTo, side, duration) =>
        context.log.info("[Iteration #{}] Received neighbor's right side in {}s", iteration, duration.toMillis / 1000f)
        if (neighborsSides._2.isDefined) {
          context.pipeToSelf(Future(computeNextIteration(field, side, neighborsSides._2.get))) {
            case Success(result)    => IterationCompleted(replyTo, result)
            case Failure(exception) => throw exception
          }

          Behaviors.same
        } else
          multiWorkerSimulationBehaviour(workerNeighbors, field, iteration, neighborsSides.copy(_1 = Some(side)))

      case UpdateRightSide(replyTo, side, duration) =>
        context.log.info("[Iteration #{}] Received neighbor's left side in {}s", iteration, duration.toMillis / 1000f)
        if (neighborsSides._1.isDefined) {
          context.pipeToSelf(Future(computeNextIteration(field, side, neighborsSides._1.get))) {
            case Success(result)    => IterationCompleted(replyTo, result)
            case Failure(exception) => throw exception
          }

          Behaviors.same
        } else
          multiWorkerSimulationBehaviour(workerNeighbors, field, iteration, neighborsSides.copy(_2 = Some(side)))

      case IterationCompleted(replyTo, result) =>
        context.log.info("[Iteration #{}] Completed in {}s with {} population", iteration, result._3.toMillis / 1000f, result._2)

        replyTo ! WorkerIterationResult(context.self, WorkerIterationStats(result._3, result._2))
        multiWorkerSimulationBehaviour(workerNeighbors, field, iteration, (None, None), result._1)

      case TellFieldLeftSide(replyTo) =>
        replyTo ! field.map(_.head).toList
        Behaviors.same

      case TellFieldRightSide(replyTo) =>
        replyTo ! field.map(_.last).toList
        Behaviors.same

      case newSimulation: NewSimulation => handleNewSimulationCommand(newSimulation, initialBehaviour())

      case Reset =>
        context.log.info("[Reset] Received Reset command from master. Resetting to empty state")
        initialBehaviour()

      case ShowYourField => handleShowCommand(if (nextField.isEmpty) field else nextField)

      case AskingFailure(throwable) =>
        context.log.error("Failure during neighbor communication", throwable)
        Behaviors.stopped

      case Die(reason) => handleDieCommand(reason)

      case wrong => handleWrongCommand(wrong, "multiWorkerSimulation")
    }

  private[this] def singleWorkerSimulationBehaviour(field: Field): Behavior[WorkerCommand] =
    Behaviors.receiveMessage {
      case TellStatus(replyTo) =>
        replyTo ! WorkerBehaviour.Processing(standAlone = true)
        Behaviors.same

      case NextIteration(replyTo, next) =>
        context.log.debug("[Iteration #{}] Received NextIteration command", next)

        context.pipeToSelf(Future(computeNextIteration(field, ArraySeq.empty, ArraySeq.empty, standAlone = true))) {
          case Success(result)    => IterationCompleted(replyTo, result)
          case Failure(exception) => throw exception
        }

        Behaviors.same

      case newSimulation: NewSimulation => handleNewSimulationCommand(newSimulation, initialBehaviour())

      case IterationCompleted(replyTo, result) =>
        replyTo ! WorkerIterationResult(context.self, WorkerIterationStats(result._3, result._2))
        singleWorkerSimulationBehaviour(result._1)

      case ShowYourField => handleShowCommand(field)

      case Reset =>
        context.log.info("[Reset] Received Reset command from master. Resetting to empty state")
        initialBehaviour()

      case Die(reason) => handleDieCommand(reason)

      case wrong => handleWrongCommand(wrong, "singleWorkerSimulation")
    }
}
