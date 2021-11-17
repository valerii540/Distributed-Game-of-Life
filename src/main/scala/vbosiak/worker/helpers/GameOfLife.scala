package vbosiak.worker.helpers

import vbosiak.common.utils.Clock
import vbosiak.worker.actors.Worker.{Field, Side}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.ArraySeq
import scala.collection.parallel.CollectionConverters._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

object GameOfLife {
  def computeNextIteration(
      field: Field,
      leftSide: Side,
      rightSide: Side,
      standAlone: Boolean = false
  )(implicit ec: ExecutionContext): Future[(Field, Int, FiniteDuration)] = Future {
    val ((finishedField, population), duration) = Clock.withMeasuring {
      val fieldCopy  = field.map(_.toArray)
      val population = new AtomicInteger(0)

      val parField = field.zipWithIndex.par
//      parField.tasksupport = new ExecutionContextTaskSupport(ExecutionContext.fromExecutor(Executors.newFixedThreadPool(1)))

      parField.foreach { case (row, r) =>
//      field.zipWithIndex.foreach { case (row, r) =>
//        if (r % 1000== 0) println(s"$r rows passed...")
        for (c <- row.indices) {
          val isAlive = row(c)
//          val aliveNeighbors: Int = Seq(
//            computeCell(field, r - 1, c, standAlone),                // top
//            computeCell(field, r + 1, c, standAlone),                // bottom
//            computeCell(field, r, c + 1, standAlone, rightSide),     // right
//            computeCell(field, r, c - 1, standAlone, leftSide),      // left
//            computeCell(field, r - 1, c + 1, standAlone, rightSide), // top-right
//            computeCell(field, r - 1, c - 1, standAlone, leftSide),  // top-left
//            computeCell(field, r + 1, c + 1, standAlone, rightSide), // bottom-right
//            computeCell(field, r + 1, c - 1, standAlone, leftSide)   // bottom-left
//          ).count(identity)
          val iterator = Iterator(
            computeCell(field, r - 1, c, standAlone),                // top
            computeCell(field, r + 1, c, standAlone),                // bottom
            computeCell(field, r, c + 1, standAlone, rightSide),     // right
            computeCell(field, r, c - 1, standAlone, leftSide),      // left
            computeCell(field, r - 1, c + 1, standAlone, rightSide), // top-right
            computeCell(field, r - 1, c - 1, standAlone, leftSide),  // top-left
            computeCell(field, r + 1, c + 1, standAlone, rightSide), // bottom-right
            computeCell(field, r + 1, c - 1, standAlone, leftSide)   // bottom-left
          )

          var aliveNeighbors = 0
          while (aliveNeighbors < 4 && iterator.hasNext)
            if (iterator.next()) aliveNeighbors += 1

          if (isAlive && (aliveNeighbors == 2 || aliveNeighbors == 3))
            population.incrementAndGet()
          else if (!isAlive && aliveNeighbors == 3) {
            fieldCopy(r)(c) = true
            population.incrementAndGet()
          } else
            fieldCopy(r)(c) = false
        }
      }

      fieldCopy.map(_.to(ArraySeq)) -> population.get()
    }
    (finishedField, population, duration)
  }

  def computeCell(field: Field, r: Int, c: Int, standAlone: Boolean, neighborSide: Side = ArraySeq.empty): Boolean =
    (field.isDefinedAt(r), field.head.isDefinedAt(c)) match {
      // Cases in both modes
      case (true, true)  => field(r)(c)
      case (false, true) => if (r >= field.size) field.head(c) else field.last(c)

      // Cases in multi-worker mode
      case (true, false) if !standAlone  => neighborSide(r)
      case (false, false) if !standAlone => neighborSide.head

      // Cases in stand-alone mode
      case (true, false) => if (c >= field.head.size) field(r).head else field(r).last
      case _             =>
        if (r >= field.size && c >= field.head.size) field.head.head
        else if (r < field.size && c >= field.head.size) field.last.head
        else if (r >= field.size && c < field.head.size) field.head.last
        else field.last.last
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
