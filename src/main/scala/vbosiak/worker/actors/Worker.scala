package vbosiak.worker.actors

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.typed.Cluster
import akka.util.Timeout
import vbosiak.common.models._
import vbosiak.common.utils.ResourcesInspector
import vbosiak.worker.models.WorkerBehaviour

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}

object Worker {
  type Field          = Vector[Vector[Boolean]]
  type NeighborsSides = (Option[Vector[Boolean]], Option[Vector[Boolean]])

  sealed trait WorkerCommand extends CborSerializable

  final case class TellCapabilities(replyTo: ActorRef[Capabilities])                            extends WorkerCommand
  final case class TellStatus(replyTo: ActorRef[WorkerBehaviour])                               extends WorkerCommand
  final case class NewSimulation(replyTo: ActorRef[Done], fieldSize: Int, neighbors: Neighbors) extends WorkerCommand
  final case class NextIteration(replyTo: ActorRef[WorkerIterationResult])                      extends WorkerCommand
  final case class TellFieldLeftSide(replyTo: ActorRef[Vector[Boolean]])                        extends WorkerCommand
  final case class TellFieldRightSide(replyTo: ActorRef[Vector[Boolean]])                       extends WorkerCommand

  private final case class UpdateLeftSide(replyTo: ActorRef[WorkerIterationResult], side: Vector[Boolean])  extends WorkerCommand
  private final case class UpdateRightSide(replyTo: ActorRef[WorkerIterationResult], side: Vector[Boolean]) extends WorkerCommand
  private final case class AskingFailure(throwable: Throwable)                                              extends WorkerCommand

  def apply(cluster: Cluster): Behavior[WorkerCommand] =
    Behaviors.setup { context =>
      context.log.info("Hello, I'm worker {} at {}", context.self.path, cluster.selfMember.address)

      initialLifeCycle()
    }

  private def initialLifeCycle(): Behavior[WorkerCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case TellStatus(replyTo) =>
          replyTo ! WorkerBehaviour.Idle
          Behaviors.same

        case TellCapabilities(replyTo) =>
          replyTo ! ResourcesInspector.processingCapabilities
          Behaviors.same

        case NewSimulation(replyTo, fieldSize, neighbors) =>
          val field = Vector.tabulate(fieldSize, fieldSize)((_, _) => false)

          context.log.info("Initialized {}x{} empty field", field.length, field.head.length)

          replyTo ! Done

          //TODO: check comparison
          if (neighbors.left == context.self && neighbors.right == context.self) {
            context.log.info("Working in the single worker mode")
            singleWorkerSimulationBehaviour(field)
          } else {
            context.log.debug("Working in the multi worker mode")
            multiWorkerSimulationBehaviour(neighbors, Vector.empty, (None, None), field)
          }

        case wrong =>
          context.log.warn("Received massage in wrong behaviour: {}", wrong)
          Behaviors.same
      }
    }

  private def multiWorkerSimulationBehaviour(
      workerNeighbors: Neighbors,
      field: Field,
      neighborsSides: NeighborsSides = (None, None),
      nextField: Field = Vector.empty
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
          initialLifeCycle()

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

  def computeNextIteration(field: Field, leftSide: Vector[Boolean], rightSide: Vector[Boolean]): (Field, WorkerIterationStats) = {
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

    val finishedField = fieldCopy.map(_.toVector)
    val duration      = FiniteDuration(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)

    finishedField -> WorkerIterationStats(duration, population)
  }

  private def computeCell(field: Field, r: Int, c: Int, neighborSide: Vector[Boolean] = Vector.empty): Boolean =
    (field.isDefinedAt(r), field.head.isDefinedAt(c)) match {
      case (true, true)   => field(r)(c)
      case (true, false)  => neighborSide(r)
      case (false, true)  => if (r >= field.size) field.head(c) else field.last(c)
      case (false, false) => neighborSide.head
    }
}
