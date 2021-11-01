package vbosiak.worker.actors

import akka.Done
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.Timeout
import vbosiak.common.models._
import vbosiak.common.utils.FieldFormatter._
import vbosiak.common.utils.{Clock, ResourcesInspector}
import vbosiak.master.models.Size
import vbosiak.worker.actors.Worker.WorkerCommand
import vbosiak.worker.helpers.WorkerHelper
import vbosiak.worker.models.WorkerBehaviour

import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Random, Success}

object Worker {
  type WorkerId       = String
  type Field          = ArraySeq[ArraySeq[Boolean]]
  type Side           = List[Boolean]
  type NeighborsSides = (Option[Side], Option[Side])

  val workerServiceKey: ServiceKey[WorkerCommand] = ServiceKey("worker")

  sealed trait WorkerCommand extends CborSerializable

  case object Reset                                               extends WorkerCommand
  final case class Die(reason: String)                            extends WorkerCommand
  final case class TellAboutYou(replyTo: ActorRef[Capabilities])  extends WorkerCommand
  final case class TellStatus(replyTo: ActorRef[WorkerBehaviour]) extends WorkerCommand

  final case class NewSimulation(replyTo: ActorRef[Int], fieldSize: Size, lifeFactor: Float, neighbors: Option[Neighbors], seed: Option[Int])
      extends WorkerCommand
  final case class NextIteration(replyTo: ActorRef[WorkerIterationResult]) extends WorkerCommand
  final case class TellFieldLeftSide(replyTo: ActorRef[Side])              extends WorkerCommand
  final case class TellFieldRightSide(replyTo: ActorRef[Side])             extends WorkerCommand

  case object ShowYourField                                                       extends WorkerCommand
  final case class PrepareSelfTest(replyTo: ActorRef[Done], neighbors: Neighbors) extends WorkerCommand

  private final case class UpdateLeftSide(replyTo: ActorRef[WorkerIterationResult], side: Side)  extends WorkerCommand
  private final case class UpdateRightSide(replyTo: ActorRef[WorkerIterationResult], side: Side) extends WorkerCommand
  private final case class AskingFailure(throwable: Throwable)                                   extends WorkerCommand

  def apply(): Behavior[WorkerCommand] =
    Behaviors.setup { context =>
      context.log.info("Hello, I'm worker {}", context.self.path.name)

      context.system.receptionist ! Receptionist.Register(workerServiceKey, context.self)

      new Worker()(context).initialBehaviour()
    }
}

private final class Worker()(override implicit val context: ActorContext[WorkerCommand]) extends WorkerHelper {
  import Worker._

  private def initialBehaviour(): Behavior[WorkerCommand] =
    Behaviors.receiveMessage {
      case TellStatus(replyTo) =>
        replyTo ! WorkerBehaviour.Idle
        Behaviors.same

      case TellAboutYou(replyTo) =>
        replyTo ! ResourcesInspector.processingCapabilities
        Behaviors.same

      case NewSimulation(replyTo, fieldSize, lifeFactor, neighbors, seed) =>
        seed.foreach { s =>
          context.log.info("Initializing with seed: {}", s)
          Random.setSeed(s)
        }

        val (field, duration) = Clock.withMeasuring {
          ArraySeq.tabulate(fieldSize.height, fieldSize.width)((_, _) => Random.between(0f, 1f) <= lifeFactor)
        }

        context.log.info("Initialized {}x{} field in {}s", field.size, field.head.size, duration.toMillis / 1000f)

        replyTo ! field.foldLeft(0)((acc, row) => acc + row.count(identity))

        if (neighbors.isEmpty) {
          context.log.info("Working in the stand-alone mode")
          singleWorkerSimulationBehaviour(field)
        } else {
          context.log.info("Working in the multi worker mode with {}", neighbors.get)
          multiWorkerSimulationBehaviour(neighbors.get, ArraySeq.empty, (None, None), field)
        }

      case PrepareSelfTest(replyTo, neighbors) =>
        context.log.info("[Self-test] Preparing self-test with neighbors: {}", neighbors)
        replyTo ! Done
        multiWorkerSimulationBehaviour(neighbors, ArraySeq.empty, (None, None), generateStableTestSample)

      case Die(reason) => handleDieCommand(reason)

      case wrong => handleWrongCommand(wrong, "initial")
    }

  private[this] def multiWorkerSimulationBehaviour(
      workerNeighbors: Neighbors,
      field: Field,
      neighborsSides: NeighborsSides = (None, None),
      nextField: Field = ArraySeq.empty
  ): Behavior[WorkerCommand] =
    Behaviors.receiveMessage {
      case TellStatus(replyTo) =>
        replyTo ! WorkerBehaviour.Processing(standAlone = false)
        Behaviors.same

      case NextIteration(replyTo) =>
        context.log.debug("Received NextIteration command")
        implicit val timeout: Timeout = 5.seconds

        // Ask left worker about it's right side
        context.ask(workerNeighbors.left, TellFieldRightSide) {
          case Success(side)      => UpdateLeftSide(replyTo, side)
          case Failure(exception) => AskingFailure(exception)
        }

        // Ask right worker about it's left side
        context.ask(workerNeighbors.right, TellFieldLeftSide) {
          case Success(side)      => UpdateRightSide(replyTo, side)
          case Failure(exception) => AskingFailure(exception)
        }

        multiWorkerSimulationBehaviour(workerNeighbors, nextField)

      case UpdateLeftSide(replyTo, side) =>
        if (neighborsSides._2.isDefined) {
          context.log.debug("All neighbor sides are ready:\nLeft: {}\nRight: {}", side.beautify, neighborsSides._2.get.beautify)
          val ((newField, population), duration) = Clock.withMeasuring {
            computeNextIteration(field, side, neighborsSides._2.get)
          }

          replyTo ! WorkerIterationResult(context.self, WorkerIterationStats(duration, population))

          multiWorkerSimulationBehaviour(workerNeighbors, field, (None, None), newField)
        } else
          multiWorkerSimulationBehaviour(workerNeighbors, field, neighborsSides.copy(_1 = Some(side)))

      case UpdateRightSide(replyTo, side) =>
        if (neighborsSides._1.isDefined) {
          context.log.debug("All neighbor sides are ready:\nLeft: {}\nRight: {}", neighborsSides._1.get.beautify, side.beautify)
          val ((newField, population), duration) = Clock.withMeasuring {
            computeNextIteration(field, side, neighborsSides._1.get)
          }

          replyTo ! WorkerIterationResult(context.self, WorkerIterationStats(duration, population))

          multiWorkerSimulationBehaviour(workerNeighbors, field, (None, None), newField)
        } else
          multiWorkerSimulationBehaviour(workerNeighbors, field, neighborsSides.copy(_2 = Some(side)))

      case TellFieldLeftSide(replyTo) =>
        replyTo ! field.map(_.head).toList
        Behaviors.same

      case TellFieldRightSide(replyTo) =>
        replyTo ! field.map(_.last).toList
        Behaviors.same

      case newSimulation: NewSimulation => handleNewSimulationCommand(newSimulation, initialBehaviour())

      case Reset =>
        context.log.info("Received Reset command from master. Resetting to empty state")
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

      case NextIteration(replyTo) =>
        context.log.debug("Received NextIteration command")
        val ((newField, population), duration) = Clock.withMeasuring {
          computeNextIteration(field, Nil, Nil, standAlone = true)
        }

        replyTo ! WorkerIterationResult(context.self, WorkerIterationStats(duration, population))

        singleWorkerSimulationBehaviour(newField)

      case newSimulation: NewSimulation => handleNewSimulationCommand(newSimulation, initialBehaviour())

      case ShowYourField => handleShowCommand(field)

      case Reset =>
        context.log.info("Received Reset command from master. Resetting to empty state")
        initialBehaviour()

      case Die(reason) => handleDieCommand(reason)

      case wrong => handleWrongCommand(wrong, "singleWorkerSimulation")
    }
}
