package vbosiak.worker.helpers

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.cluster.typed.{Cluster, Leave}
import vbosiak.common.utils.FieldFormatter._
import vbosiak.worker.actors.Worker._

import scala.collection.immutable.ArraySeq

private[worker] trait WorkerHelper {
  implicit val context: ActorContext[WorkerCommand]

  def handleDieCommand(reason: String): Behavior[WorkerCommand] = {
    val cluster = Cluster(context.system)
    context.log.error("Received poison pill from master because of \"{}\"", reason)
    cluster.manager ! Leave(cluster.selfMember.address)
    Behaviors.same
  }

  def handleWrongCommand(command: WorkerCommand, currentBehaviourName: String): Behavior[WorkerCommand] = {
    context.log.warn("Received {} in {} behaviour: {}", command, currentBehaviourName)
    Behaviors.same
  }

  def handleShowCommand(field: Field): Behavior[WorkerCommand] = {
    context.log.info("Received show command:\n{}", field.beautify)
    Behaviors.same
  }

  def handleNewSimulationCommand(command: NewSimulation, initialBehaviour: Behavior[WorkerCommand]): Behavior[WorkerCommand] = {
    context.log.info("Resetting self to initial state and start preparing new simulation")
    context.self ! command
    initialBehaviour
  }

  def computeNextIteration(
      field: Field,
      leftSide: ArraySeq[Boolean],
      rightSide: ArraySeq[Boolean],
      standAlone: Boolean = false
  ): (Field, Int) = {
    val fieldCopy  = field.map(_.toArray)
    var population = 0

    for (r <- field.indices; c <- field(r).indices) {
      val isAlive = field(r)(c)

      val aliveNeighbors: Int = Seq(
        computeCell(field, r - 1, c, ArraySeq.empty, standAlone), // top
        computeCell(field, r + 1, c, ArraySeq.empty, standAlone), // bottom
        computeCell(field, r, c + 1, rightSide, standAlone),      // right
        computeCell(field, r, c - 1, leftSide, standAlone),       // left
        computeCell(field, r - 1, c + 1, rightSide, standAlone),  // top-right
        computeCell(field, r - 1, c - 1, leftSide, standAlone),   // top-left
        computeCell(field, r + 1, c + 1, rightSide, standAlone),  // bottom-right
        computeCell(field, r + 1, c - 1, leftSide, standAlone)    // bottom-left
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

    finishedField -> population
  }

  def computeCell(field: Field, r: Int, c: Int, neighborSide: ArraySeq[Boolean] = ArraySeq.empty, standAlone: Boolean = false): Boolean =
    (field.isDefinedAt(r), field.head.isDefinedAt(c)) match {
      // Cases in both modes
      case (true, true)  => field(r)(c)
      case (false, true) => if (r >= field.size) field.head(c) else field.last(c)

      // Cases in multi-worker mode
      case (true, false) if !standAlone  => neighborSide(r)
      case (false, false) if !standAlone => neighborSide.head

      // Cases in stand-alone mode
      case (true, false)                                             => if (c >= field.head.size) field(r).head else field(r).last
      case (false, false) if r >= field.size && c >= field.head.size => field.head.head
      case (false, false) if r < field.size && c >= field.head.size  => field.last.head
      case (false, false) if r >= field.size && c < field.head.size  => field.head.last
      case (false, false) if r < field.size && c < field.head.size   => field.last.last
    }
}
