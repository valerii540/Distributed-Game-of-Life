package vbosiak.worker.actors

import akka.Done
import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.typed.Cluster
import akka.util.Timeout
import vbosiak.common.models.{CborSerializable, Neighbors, WorkerIterationResult, WorkerIterationStats}
import vbosiak.common.utils.ResourcesInspector
import vbosiak.master.actors.Master.{MasterCommand, WorkerCapabilities}

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object Worker {
  type WorkerTopic = Topic.Command[Worker.WorkerCommand]
  type Field       = Vector[Vector[Boolean]]

  sealed trait WorkerCommand extends CborSerializable

  final case class TellCapabilities(replyTo: ActorRef[MasterCommand])                           extends WorkerCommand
  final case class NewSimulation(replyTo: ActorRef[Done], fieldSize: Int, neighbors: Neighbors) extends WorkerCommand
  final case class NextIteration(replyTo: ActorRef[WorkerIterationResult])                      extends WorkerCommand
  final case class TellFieldLeftSide(replyTo: ActorRef[Vector[Boolean]])                        extends WorkerCommand
  final case class TellFieldRightSide(replyTo: ActorRef[Vector[Boolean]])                       extends WorkerCommand

  private final case class UpdateLeftSide(replyTo: ActorRef[WorkerIterationResult], side: Vector[Boolean])  extends WorkerCommand
  private final case class UpdateRightSide(replyTo: ActorRef[WorkerIterationResult], side: Vector[Boolean]) extends WorkerCommand
  private final case class AskingFailure(throwable: Throwable)                                              extends WorkerCommand

  def apply(cluster: Cluster, workerTopic: ActorRef[WorkerTopic]): Behavior[WorkerCommand] =
    Behaviors.setup { context =>
      context.log.info("Hello, I'm worker {} at {}", context.self.path, cluster.selfMember.address)

      workerTopic ! Topic.Subscribe(context.self)

      initialLifeCycle()
    }

  def initialLifeCycle(): Behavior[WorkerCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessagePartial { case TellCapabilities(master) =>
        master ! WorkerCapabilities(ResourcesInspector.processingCapabilities, context.self)
        initialLifeCycle(master)
      }
    }

  def initialLifeCycle(master: ActorRef[MasterCommand]): Behavior[WorkerCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessagePartial { case NewSimulation(replyTo, fieldSize, neighbors) =>
        val field = Vector.tabulate(fieldSize, fieldSize)((_, _) => false)

        context.log.info("Initialized {}x{} empty field", field.length, field.head.length)

        replyTo ! Done

        //TODO: check comparison
        if (neighbors.left == context.self && neighbors.right == context.self) {
          context.log.info("Working in the single worker mode")
          singleWorkerSimulationBehaviour(master, field)
        } else {
          context.log.debug("Working in the multi worker mode")
          multiWorkerSimulationBehaviour(neighbors, field, (None, None))
        }
      }
    }

  def multiWorkerSimulationBehaviour(
      workerNeighbors: Neighbors,
      field: Field,
      neighborsSides: (Option[Vector[Boolean]], Option[Vector[Boolean]])
  ): Behavior[WorkerCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
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

          Behaviors.same

        case UpdateLeftSide(replyTo, side) =>
          if (neighborsSides._2.isDefined)
            computeNextIterationBehaviour(replyTo, workerNeighbors, field, (side, neighborsSides._2.get))
          else
            multiWorkerSimulationBehaviour(workerNeighbors, field, neighborsSides.copy(_1 = Some(side)))

        case UpdateRightSide(replyTo, side) =>
          if (neighborsSides._1.isDefined)
            computeNextIterationBehaviour(replyTo, workerNeighbors, field, (neighborsSides._1.get, side))
          else
            multiWorkerSimulationBehaviour(workerNeighbors, field, neighborsSides.copy(_2 = Some(side)))
      }

    }

  def singleWorkerSimulationBehaviour(master: ActorRef[MasterCommand], field: Field): Behavior[WorkerCommand] = ???

  def computeNextIterationBehaviour(
      replyTo: ActorRef[WorkerIterationResult],
      workerNeighbors: Neighbors,
      field: Field,
      neighborsSides: (Vector[Boolean], Vector[Boolean])
  ): Behavior[WorkerCommand] =
    Behaviors.setup { context =>
      val (newField, stats) = computeNextIteration(field, neighborsSides._1, neighborsSides._2)

      replyTo ! WorkerIterationResult(context.self, Right(stats))
      multiWorkerSimulationBehaviour(workerNeighbors, newField, (None, None))
    }

  def computeNextIteration(field: Field, leftSide: Vector[Boolean], rightSide: Vector[Boolean]): (Field, WorkerIterationStats) = {
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

    fieldCopy.map(_.toVector) -> WorkerIterationStats(population)
  }

  def computeCell(field: Field, r: Int, c: Int, neighborSide: Vector[Boolean] = Vector.empty): Boolean =
    (field.isDefinedAt(r), field.head.isDefinedAt(c)) match {
      case (true, true)   => field(r)(c)
      case (true, false)  => neighborSide(r)
      case (false, true)  => if (r >= field.size) field.head(c) else field.last(c)
      case (false, false) => neighborSide.head
    }
}
