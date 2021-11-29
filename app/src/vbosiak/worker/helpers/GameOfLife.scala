package vbosiak.worker.helpers

import vbosiak.common.utils.Clock
import vbosiak.master.controllers.models.Size
import vbosiak.worker.actors.Worker.{Field, Side}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.ArraySeq
import scala.collection.parallel.CollectionConverters._
import scala.concurrent.duration.FiniteDuration
import scala.util.Random

private[worker] object GameOfLife {
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
    def mutate(isAlive: Boolean, population: AtomicInteger, copy: ArraySeq[Array[Boolean]], aliveNeighbors: Int, r: Int, c: Int): Unit =
      if (isAlive && (aliveNeighbors == 2 || aliveNeighbors == 3))
        population.incrementAndGet()
      else if (!isAlive && aliveNeighbors == 3) {
        copy(r)(c) = true
        population.incrementAndGet()
      } else
        copy(r)(c) = false

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
        val lastR = field.size - 1

        mutate(
          field.last(c),
          population,
          copy,
          Seq(
            field(lastR - 1)(c),     // top
            field(0)(c),             // bottom
            field(lastR)(c + 1),     // right
            field(lastR)(c - 1),     // left
            field(lastR - 1)(c + 1), // top-right
            field(lastR - 1)(c - 1), // top-left
            field(0)(c + 1),         // bottom-right
            field(0)(c - 1)          // bottom-left
          ).count(identity),
          lastR,
          c
        )
      }

    def computeInnerRow(row: ArraySeq[Boolean], r: Int, population: AtomicInteger, copy: ArraySeq[Array[Boolean]]): Unit =
      (1 until row.size - 1).foreach { c =>
        mutate(
          isAlive = row(c),
          population = population,
          copy = copy,
          aliveNeighbors = Seq(
            field(r - 1)(c),     // top
            field(r + 1)(c),     // bottom
            field(r)(c + 1),     // right
            field(r)(c - 1),     // left
            field(r - 1)(c + 1), // top-right
            field(r - 1)(c - 1), // top-left
            field(r + 1)(c + 1), // bottom-right
            field(r + 1)(c - 1)  // bottom-left
          ).count(identity),
          r = r,
          c = c
        )
      }

    def computeLeftCell(r: Int, population: AtomicInteger, copy: ArraySeq[Array[Boolean]]): Unit =
      mutate(
        field(r).head,
        population,
        copy,
        Seq(
          field(r - 1).head,                                                    // top
          field(r + 1).head,                                                    // bottom
          field(r)(1),                                                          // right
          if (standAlone) field(r)(field(r).size - 1) else leftSide(r),         // left
          field(r - 1)(1),                                                      // top-right
          if (standAlone) field(r - 1)(field(r).size - 1) else leftSide(r - 1), // top-left
          field(r + 1)(1),                                                      // bottom-right
          if (standAlone) field(r + 1)(field(r).size - 1) else leftSide(r + 1)  // bottom-left
        ).count(identity),
        r,
        0
      )

    def computeRightCell(r: Int, population: AtomicInteger, copy: ArraySeq[Array[Boolean]]): Unit = {
      val lastIdx = field(r).size - 1

      mutate(
        field(r).last,
        population,
        copy,
        Seq(
          field(r - 1).last,                                     // top
          field(r + 1).last,                                     // bottom
          if (standAlone) field(r)(0) else rightSide(r),         // right
          field(r)(lastIdx - 1),                                 // left
          if (standAlone) field(r - 1)(0) else rightSide(r - 1), // top-right
          field(r - 1)(lastIdx - 1),                             // top-left
          if (standAlone) field(r + 1)(0) else rightSide(r + 1), // bottom-right
          field(r + 1)(lastIdx - 1)                              // bottom-left
        ).count(identity),
        r,
        lastIdx
      )
    }

    def computeCorners(population: AtomicInteger, copy: ArraySeq[Array[Boolean]]): Unit = {
      val topR    = 0
      val bottomR = field.size - 1

      val leftC  = 0
      val rightC = field.head.size - 1

      // TOP LEFT
      mutate(
        field(topR)(leftC),
        population,
        copy,
        Seq(
          field(bottomR)(leftC),                                          // top
          field(topR + 1)(leftC),                                         // bottom
          field(topR)(leftC + 1),                                         // right
          if (standAlone) field(topR)(rightC) else leftSide(topR),        // left
          field(bottomR)(leftC + 1),                                      // top-right
          if (standAlone) field(bottomR)(rightC) else leftSide(bottomR),  // top-left
          field(topR + 1)(leftC + 1),                                     // bottom-right
          if (standAlone) field(topR + 1)(rightC) else leftSide(topR + 1) // bottom-left
        ).count(identity),
        topR,
        leftC
      )

      // TOP RIGHT
      mutate(
        field(topR)(rightC),
        population,
        copy,
        Seq(
          field(bottomR)(rightC),                                          // top
          field(1)(rightC),                                                // bottom
          if (standAlone) field(topR)(leftC) else rightSide(topR),         // right
          field(topR)(rightC - 1),                                         // left
          if (standAlone) field(bottomR)(leftC) else rightSide(bottomR),   // top-right
          field(bottomR)(rightC - 1),                                      // top-left
          if (standAlone) field(topR + 1)(leftC) else rightSide(topR + 1), // bottom-right
          field(topR + 1)(rightC - 1)                                      // bottom-left
        ).count(identity),
        topR,
        rightC
      )

      // BOTTOM LEFT
      mutate(
        field(bottomR)(leftC),
        population,
        copy,
        Seq(
          field(bottomR - 1)(leftC),                                             // top
          field(topR)(leftC),                                                    // bottom
          field(bottomR)(leftC + 1),                                             // right
          if (standAlone) field(bottomR)(rightC) else leftSide(bottomR),         // left
          field(bottomR - 1)(leftC + 1),                                         // top-right
          if (standAlone) field(bottomR - 1)(rightC) else leftSide(bottomR - 1), // top-left
          field(topR)(leftC + 1),                                                // bottom-right
          if (standAlone) field(topR)(rightC) else leftSide(topR)                // bottom-left
        ).count(identity),
        bottomR,
        leftC
      )

      // BOTTOM RIGHT
      mutate(
        field(bottomR)(rightC),
        population,
        copy,
        Seq(
          field(bottomR - 1)(rightC),                                            // top
          field(topR)(rightC),                                                   // bottom
          if (standAlone) field(bottomR)(leftC) else rightSide(bottomR),         // right
          field(bottomR)(rightC - 1),                                            // left
          if (standAlone) field(bottomR - 1)(leftC) else rightSide(bottomR - 1), // top-right
          field(bottomR - 1)(rightC - 1),                                        // top-left
          if (standAlone) field(topR)(leftC) else rightSide(topR),               // bottom-right
          field(topR)(rightC - 1)                                                // bottom-left
        ).count(identity),
        bottomR,
        rightC
      )
    }

    val ((finishedField, population), duration) = Clock.withMeasuring {

      val fieldCopy  = field.map(_.toArray)
      val population = new AtomicInteger(0)

      computeInnerTop(population, fieldCopy)
      computeInnerBottom(population, fieldCopy)
      computeCorners(population, fieldCopy)

      if (inParallel)
        (1 until field.size - 1).par.foreach { r =>
          computeLeftCell(r, population, fieldCopy)
          computeInnerRow(field(r), r, population, fieldCopy)
          computeRightCell(r, population, fieldCopy)
        }
      else
        (1 until field.size - 1).foreach { r =>
          computeLeftCell(r, population, fieldCopy)
          computeInnerRow(field(r), r, population, fieldCopy)
          computeRightCell(r, population, fieldCopy)
        }

      fieldCopy.map(_.to(ArraySeq)) -> population.get()
    }
    (finishedField, population, duration)
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
