package vbosiak.worker.helpers

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.cluster.typed.{Cluster, Leave}
import vbosiak.common.utils.Clock
import vbosiak.common.utils.FieldFormatter._
import vbosiak.master.controllers.models.Size
import vbosiak.worker.actors.Worker._

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.ArraySeq
import scala.collection.parallel.CollectionConverters._
import scala.concurrent.duration.FiniteDuration
import scala.util.Random

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

  def createNewField(size: Size, lifeFactor: Float, seed: Option[Long]): Field = {
    seed.foreach(Random.setSeed)

    ArraySeq.tabulate(size.height, size.width)((_, _) => Random.between(0f, 1f) <= lifeFactor)
  }

  def computeNextIteration(
      field: Field,
      leftSide: Side,
      rightSide: Side,
      standAlone: Boolean = false,
      inParallel: Boolean = true
  ): (Field, Int, FiniteDuration) = {
//    def computeRow(row: ArraySeq[Boolean], r: Int, population: AtomicInteger, copy: ArraySeq[Array[Boolean]]): Unit =
//      for (c <- row.indices) {
//        val isAlive = row(c)
//
//        val aliveNeighbors: Int = Seq(
//          checkCell(field, r - 1, c, standAlone),                // top
//          checkCell(field, r + 1, c, standAlone),                // bottom
//          checkCell(field, r, c + 1, standAlone, rightSide),     // right
//          checkCell(field, r, c - 1, standAlone, leftSide),      // left
//          checkCell(field, r - 1, c + 1, standAlone, rightSide), // top-right
//          checkCell(field, r - 1, c - 1, standAlone, leftSide),  // top-left
//          checkCell(field, r + 1, c + 1, standAlone, rightSide), // bottom-right
//          checkCell(field, r + 1, c - 1, standAlone, leftSide)   // bottom-left
//        ).count(identity)
//
//        if (isAlive && (aliveNeighbors == 2 || aliveNeighbors == 3))
//          population.incrementAndGet()
//        else if (!isAlive && aliveNeighbors == 3) {
//          copy(r)(c) = true
//          population.incrementAndGet()
//        } else
//          copy(r)(c) = false
//      }

    def computeInnerTop(population: AtomicInteger, copy: ArraySeq[Array[Boolean]]): Unit =
      (1 until field.head.size - 1).foreach { c =>
        val isAlive = field.head(c)

        val aliveNeighbors: Int = Seq(
          field.last(c),     // top
          field(1)(c),       // bottom
          field(0)(c + 1),   // right
          field(0)(c - 1),   // left
          field.last(c + 1), // top-right
          field.last(c - 1), // top-left
          field(1)(c + 1),   // bottom-right
          field(1)(c - 1)    // bottom-left
        ).count(identity)

        if (isAlive && (aliveNeighbors == 2 || aliveNeighbors == 3))
          population.incrementAndGet()
        else if (!isAlive && aliveNeighbors == 3) {
          copy(0)(c) = true
          population.incrementAndGet()
        } else
          copy(0)(c) = false
      }

    def computeInnerBottom(population: AtomicInteger, copy: ArraySeq[Array[Boolean]]): Unit =
      (1 until field.head.size - 1).foreach { c =>
        val isAlive  = field.last(c)
        val lastRIdx = field.size - 1

        val aliveNeighbors: Int = Seq(
          field(lastRIdx - 1)(c),     // top
          field(0)(c),                // bottom
          field(lastRIdx)(c + 1),     // right
          field(lastRIdx)(c - 1),     // left
          field(lastRIdx - 1)(c + 1), // top-right
          field(lastRIdx - 1)(c - 1), // top-left
          field(0)(c + 1),            // bottom-right
          field(0)(c - 1)             // bottom-left
        ).count(identity)

        if (isAlive && (aliveNeighbors == 2 || aliveNeighbors == 3))
          population.incrementAndGet()
        else if (!isAlive && aliveNeighbors == 3) {
          copy(lastRIdx)(c) = true
          population.incrementAndGet()
        } else
          copy(lastRIdx)(c) = false
      }

    def computeInnerRow(row: ArraySeq[Boolean], r: Int, population: AtomicInteger, copy: ArraySeq[Array[Boolean]]): Unit =
      (1 until row.size - 1).foreach { c =>
        val isAlive = row(c)

        val aliveNeighbors: Int = Seq(
          field(r - 1)(c),     // top
          field(r + 1)(c),     // bottom
          field(r)(c + 1),     // right
          field(r)(c - 1),     // left
          field(r - 1)(c + 1), // top-right
          field(r - 1)(c - 1), // top-left
          field(r + 1)(c + 1), // bottom-right
          field(r + 1)(c - 1)  // bottom-left
        ).count(identity)

        if (isAlive && (aliveNeighbors == 2 || aliveNeighbors == 3))
          population.incrementAndGet()
        else if (!isAlive && aliveNeighbors == 3) {
          copy(r)(c) = true
          population.incrementAndGet()
        } else
          copy(r)(c) = false
      }

    def computeLeftCell(r: Int, population: AtomicInteger, copy: ArraySeq[Array[Boolean]]): Unit = {
      val isAlive = field(r).head

      val aliveNeighbors: Int = Seq(
        field(r - 1).head,                                                    // top
        field(r + 1).head,                                                    // bottom
        field(r)(1),                                                          // right
        if (standAlone) field(r)(field(r).size - 1) else leftSide(r),         // left
        field(r - 1)(1),                                                      // top-right
        if (standAlone) field(r - 1)(field(r).size - 1) else leftSide(r - 1), // top-left
        field(r + 1)(1),                                                      // bottom-right
        if (standAlone) field(r + 1)(field(r).size - 1) else leftSide(r + 1)  // bottom-left
      ).count(identity)

      if (isAlive && (aliveNeighbors == 2 || aliveNeighbors == 3))
        population.incrementAndGet()
      else if (!isAlive && aliveNeighbors == 3) {
        copy(r)(0) = true
        population.incrementAndGet()
      } else
        copy(r)(0) = false
    }

    def computeRightCell(r: Int, population: AtomicInteger, copy: ArraySeq[Array[Boolean]]): Unit = {
      val isAlive = field(r).last
      val lastIdx = field(r).size - 1

      val aliveNeighbors: Int = Seq(
        field(r - 1).last,                                     // top
        field(r + 1).last,                                     // bottom
        if (standAlone) field(r)(0) else rightSide(r),         // right
        field(r)(lastIdx - 1),                                 // left
        if (standAlone) field(r - 1)(0) else rightSide(r - 1), // top-right
        field(r - 1)(lastIdx - 1),                             // top-left
        if (standAlone) field(r + 1)(0) else rightSide(r + 1), // bottom-right
        field(r + 1)(lastIdx - 1)                              // bottom-left
      ).count(identity)

      if (isAlive && (aliveNeighbors == 2 || aliveNeighbors == 3))
        population.incrementAndGet()
      else if (!isAlive && aliveNeighbors == 3) {
        copy(r)(lastIdx) = true
        population.incrementAndGet()
      } else
        copy(r)(lastIdx) = false
    }

    def computeCorners(population: AtomicInteger, copy: ArraySeq[Array[Boolean]]): Unit = {
      def mutate(isAlive: Boolean, aliveNeighbors: Int, r: Int, c: Int): Unit =
        if (isAlive && (aliveNeighbors == 2 || aliveNeighbors == 3))
          population.incrementAndGet()
        else if (!isAlive && aliveNeighbors == 3) {
          copy(r)(c) = true
          population.incrementAndGet()
        } else
          copy(r)(c) = false

      val topR    = 0
      val bottomR = field.size - 1

      val leftC  = 0
      val rightC = field.head.size - 1

      // TOP LEFT
      {
        val isAlive = field(topR)(leftC)

        val aliveNeighbors: Int = Seq(
          field(bottomR)(leftC),                                          // top
          field(topR + 1)(leftC),                                         // bottom
          field(topR)(leftC + 1),                                         // right
          if (standAlone) field(topR)(rightC) else leftSide(topR),        // left
          field(bottomR)(leftC + 1),                                      // top-right
          if (standAlone) field(bottomR)(rightC) else leftSide(bottomR),  // top-left
          field(topR + 1)(leftC + 1),                                     // bottom-right
          if (standAlone) field(topR + 1)(rightC) else leftSide(topR + 1) // bottom-left
        ).count(identity)

        mutate(isAlive, aliveNeighbors, topR, leftC)
      }

      // TOP RIGHT
      {
        val isAlive = field(topR)(rightC)

        val aliveNeighbors: Int = Seq(
          field(bottomR)(rightC),                                          // top
          field(1)(rightC),                                                // bottom
          if (standAlone) field(topR)(leftC) else rightSide(topR),         // right
          field(topR)(rightC - 1),                                         // left
          if (standAlone) field(bottomR)(leftC) else rightSide(bottomR),   // top-right
          field(bottomR)(rightC - 1),                                      // top-left
          if (standAlone) field(topR + 1)(leftC) else rightSide(topR + 1), // bottom-right
          field(topR + 1)(rightC - 1)                                      // bottom-left
        ).count(identity)

        mutate(isAlive, aliveNeighbors, topR, rightC)
      }

      // BOTTOM LEFT
      {
        val isAlive = field(bottomR)(leftC)

        val aliveNeighbors: Int = Seq(
          field(bottomR - 1)(leftC),                                             // top
          field(topR)(leftC),                                                    // bottom
          field(bottomR)(leftC + 1),                                             // right
          if (standAlone) field(bottomR)(rightC) else leftSide(bottomR),         // left
          field(bottomR - 1)(leftC + 1),                                         // top-right
          if (standAlone) field(bottomR - 1)(rightC) else leftSide(bottomR - 1), // top-left
          field(topR)(leftC + 1),                                                // bottom-right
          if (standAlone) field(topR)(rightC) else leftSide(topR)                // bottom-left
        ).count(identity)

        mutate(isAlive, aliveNeighbors, bottomR, leftC)
      }

      // BOTTOM RIGHT
      {
        val isAlive = field(bottomR)(rightC)

        val aliveNeighbors: Int = Seq(
          field(bottomR - 1)(rightC),                                            // top
          field(topR)(rightC),                                                   // bottom
          if (standAlone) field(bottomR)(leftC) else rightSide(bottomR),         // right
          field(bottomR)(rightC - 1),                                            // left
          if (standAlone) field(bottomR - 1)(leftC) else rightSide(bottomR - 1), // top-right
          field(bottomR - 1)(rightC - 1),                                        // top-left
          if (standAlone) field(topR)(leftC) else rightSide(topR),               // bottom-right
          field(topR)(rightC - 1)                                                // bottom-left
        ).count(identity)

        mutate(isAlive, aliveNeighbors, bottomR, rightC)
      }
    }

    val ((finishedField, population), duration) = Clock.withMeasuring {

      val fieldCopy  = field.map(_.toArray)
      val population = new AtomicInteger(0)

      if (inParallel) {
        computeInnerTop(population, fieldCopy)
        computeInnerBottom(population, fieldCopy)
        computeCorners(population, fieldCopy)

        (1 until field.size - 1).par.foreach { r =>
          computeLeftCell(r, population, fieldCopy)
          computeInnerRow(field(r), r, population, fieldCopy)
          computeRightCell(r, population, fieldCopy)
        }
      } else {
        computeInnerTop(population, fieldCopy)
        computeInnerBottom(population, fieldCopy)
        computeCorners(population, fieldCopy)

        (1 until field.size - 1).foreach { r =>
          computeLeftCell(r, population, fieldCopy)
          computeInnerRow(field(r), r, population, fieldCopy)
          computeRightCell(r, population, fieldCopy)
        }
      }

      fieldCopy.map(_.to(ArraySeq)) -> population.get()
    }
    (finishedField, population, duration)
  }

  def checkCell(field: Field, r: Int, c: Int, standAlone: Boolean, neighborSide: Side = ArraySeq.empty): Boolean =
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

  def generateStableTestSample: Field = {
    import vbosiak.common.utils.FieldPainter._

    A(
      A(-, -, o, o, -, -, o, o, -, -),
      A(-, -, -, -, -, -, -, -, -, -),
      A(o, -, -, -, -, -, -, -, -, o),
      A(o, -, -, -, -, -, -, -, -, o),
      A(-, -, -, -, o, o, -, -, -, -),
      A(o, -, -, -, o, o, -, -, -, o),
      A(o, -, -, -, -, -, -, -, -, o),
      A(-, -, -, -, -, -, -, -, -, -),
      A(-, -, -, -, -, -, -, -, -, -),
      A(-, -, o, o, -, -, o, o, -, -)
    )
  }
}
