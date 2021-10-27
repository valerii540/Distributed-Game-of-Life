package vbosiak.worker.actors

import akka.Done
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.typed.Cluster
import akka.util.Timeout
import vbosiak.common.models._
import vbosiak.common.utils.FieldFormatter._
import vbosiak.common.utils.{Clock, ResourcesInspector}
import vbosiak.worker.models.WorkerBehaviour

import java.util.concurrent.TimeUnit
import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Random, Success}

object Worker {
  type Field          = ArraySeq[ArraySeq[Boolean]]
  type NeighborsSides = (Option[ArraySeq[Boolean]], Option[ArraySeq[Boolean]])

  val workerServiceKey: ServiceKey[WorkerCommand] = ServiceKey("worker")

  sealed trait WorkerCommand extends CborSerializable

  case object Reset                                                                                                extends WorkerCommand
  final case class TellCapabilities(replyTo: ActorRef[Capabilities])                                               extends WorkerCommand
  final case class TellStatus(replyTo: ActorRef[WorkerBehaviour])                                                  extends WorkerCommand
  final case class NewSimulation(replyTo: ActorRef[Done], fieldSize: Int, lifeFactor: Float, neighbors: Neighbors) extends WorkerCommand
  final case class NextIteration(replyTo: ActorRef[WorkerIterationResult])                                         extends WorkerCommand
  final case class TellFieldLeftSide(replyTo: ActorRef[ArraySeq[Boolean]])                                         extends WorkerCommand
  final case class TellFieldRightSide(replyTo: ActorRef[ArraySeq[Boolean]])                                        extends WorkerCommand

  case object ShowYourField extends WorkerCommand

  private final case class UpdateLeftSide(replyTo: ActorRef[WorkerIterationResult], side: ArraySeq[Boolean])  extends WorkerCommand
  private final case class UpdateRightSide(replyTo: ActorRef[WorkerIterationResult], side: ArraySeq[Boolean]) extends WorkerCommand
  private final case class AskingFailure(throwable: Throwable)                                                extends WorkerCommand

  def apply(cluster: Cluster): Behavior[WorkerCommand] =
    Behaviors.setup { context =>
      context.log.info("Hello, I'm worker {} at {}", context.self.path, cluster.selfMember.address)

      context.system.receptionist ! Receptionist.Register(workerServiceKey, context.self)

      initialBehaviour()
    }

  private def initialBehaviour(): Behavior[WorkerCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case TellStatus(replyTo) =>
          replyTo ! WorkerBehaviour.Idle
          Behaviors.same

        case TellCapabilities(replyTo) =>
          replyTo ! ResourcesInspector.processingCapabilities
          Behaviors.same

        case NewSimulation(replyTo, fieldSize, lifeFactor, neighbors) =>
          val (field, duration) = Clock.withMeasuring {
            ArraySeq.tabulate(fieldSize, fieldSize)((_, _) => Random.between(0f, 1f) <= lifeFactor)
          }

          context.log.info("Initialized {}x{} field in {}s", field.length, field.head.length, duration.toMillis / 1000f)

          replyTo ! Done

          //TODO: check comparison
          if (neighbors.left == context.self && neighbors.right == context.self) {
            context.log.info("Working in the single worker mode")
            singleWorkerSimulationBehaviour(field)
          } else {
            context.log.info("Working in the multi worker mode")
            multiWorkerSimulationBehaviour(neighbors, ArraySeq.empty, (None, None), field)
          }

        case wrong =>
          context.log.warn("Received {} in initial behaviour: {}", wrong)
          Behaviors.same
      }
    }

  private def multiWorkerSimulationBehaviour(
      workerNeighbors: Neighbors,
      field: Field,
      neighborsSides: NeighborsSides = (None, None),
      nextField: Field = ArraySeq.empty
  ): Behavior[WorkerCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case TellStatus(replyTo) =>
          replyTo ! WorkerBehaviour.Processing(standLone = false)
          Behaviors.same

        case NextIteration(replyTo) =>
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
            val (newField, stats) = computeNextIteration(field, side, neighborsSides._2.get)

            replyTo ! WorkerIterationResult(context.self, stats)

            multiWorkerSimulationBehaviour(workerNeighbors, field, (None, None), newField)
          } else
            multiWorkerSimulationBehaviour(workerNeighbors, field, neighborsSides.copy(_1 = Some(side)))

        case UpdateRightSide(replyTo, side) =>
          if (neighborsSides._1.isDefined) {
            val (newField, stats) = computeNextIteration(field, neighborsSides._1.get, side)

            replyTo ! WorkerIterationResult(context.self, stats)

            multiWorkerSimulationBehaviour(workerNeighbors, field, (None, None), newField)
          } else
            multiWorkerSimulationBehaviour(workerNeighbors, field, neighborsSides.copy(_2 = Some(side)))

        case TellFieldLeftSide(replyTo) =>
          replyTo ! field.map(_.head)
          Behaviors.same

        case TellFieldRightSide(replyTo) =>
          replyTo ! field.map(_.last)
          Behaviors.same

        case newSimulation: NewSimulation =>
          context.log.info("Resetting self to initial state and start preparing new simulation")
          context.self ! newSimulation
          initialBehaviour()

        case Reset =>
          context.log.info("Received Reset command from master. Resetting to empty state")
          initialBehaviour()

        case ShowYourField =>
          context.log.info("Received show command:\n{}", field.beautify)
          Behaviors.same

        case AskingFailure(throwable) =>
          context.log.error("Failure during neighbor communication", throwable)
          Behaviors.stopped

        case wrong =>
          context.log.error("Received {} is multiWorkerSimulation behaviour", wrong)
          Behaviors.same
      }
    }

  private def singleWorkerSimulationBehaviour(field: Field): Behavior[WorkerCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case TellStatus(replyTo) =>
          replyTo ! WorkerBehaviour.Processing(standLone = true)
          Behaviors.same
        case _                   =>
          context.log.error("Stand-lone mode does not implemented yet")
          Behaviors.stopped
      }
    }

  def computeNextIteration(field: Field, leftSide: ArraySeq[Boolean], rightSide: ArraySeq[Boolean]): (Field, WorkerIterationStats) = {
    val startedAt = System.nanoTime()

    val fieldCopy  = field.map(_.toArray)
    var population = 0

    for (r <- field.indices; c <- field(r).indices) {
      val isAlive = field(r)(c)

      val aliveNeighbors: Int = Seq(
        computeCell(field, r - 1, c),                // top
        computeCell(field, r + 1, c),                // bottom
        computeCell(field, r, c + 1, rightSide),     // right
        computeCell(field, r, c - 1, leftSide),      // left
        computeCell(field, r - 1, c + 1, rightSide), // top-right
        computeCell(field, r - 1, c - 1, leftSide),  // top-left
        computeCell(field, r + 1, c + 1, rightSide), // bottom-right
        computeCell(field, r + 1, c - 1, leftSide)   // bottom-left
      ).count(identity)

      if (isAlive && (aliveNeighbors == 2 || aliveNeighbors == 3))
        population += 1
      else if (!isAlive && aliveNeighbors == 3) {
        fieldCopy(r)(c) = true
        population += 1
      } else
        fieldCopy(r)(c) = false
    }

    val finishedField = fieldCopy.map(_.to(ArraySeq))
    val duration      = FiniteDuration(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)

    finishedField -> WorkerIterationStats(duration, population)
  }

  private def computeCell(field: Field, r: Int, c: Int, neighborSide: ArraySeq[Boolean] = ArraySeq.empty): Boolean =
    (field.isDefinedAt(r), field.head.isDefinedAt(c)) match {
      case (true, true)   => field(r)(c)
      case (true, false)  => neighborSide(r)
      case (false, true)  => if (r >= field.size) field.head(c) else field.last(c)
      case (false, false) => neighborSide.head
    }
}
