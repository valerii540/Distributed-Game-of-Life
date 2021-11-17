package vbosiak.worker.helpers

import vbosiak.common.utils.FieldFormatter
import vbosiak.master.controllers.models.Size
import vbosiak.worker.actors.Worker.Side

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.{ArraySeq, BitSet}
import scala.collection.mutable
import scala.util.Random

object Universe {
  def apply(rows: ArraySeq[BitSet], size: Size, standAlone: Boolean): Universe =
    new Universe(rows, size, standAlone)

  def apply(size: Size, lifeFactor: Float, standAlone: Boolean = false, seed: Option[Long] = None): Universe = {
    val rows: ArraySeq[BitSet] = {
      seed.foreach(Random.setSeed)

      val rows_ = ArraySeq.fill(size.height)(mutable.BitSet.empty)

      for {
        c <- 0 until size.width
        r <- 0 until size.height
      } if (Random.between(0f, 1f) <= lifeFactor) rows_(r) += c

      rows_.map(_.toImmutable)
    }

    new Universe(rows, size, standAlone)
  }
}

final class Universe private (private val rows: ArraySeq[BitSet], private val size: Size, standAlone: Boolean) {
  def beautify: String = {
    val header   = ArraySeq("  " +: Seq.tabulate(size.width)(i => "%2s".format((i + 1).toString)): _*)
    val withRowN = rows
      .map(bs =>
        (0 until size.width).map(c => if (bs(c)) "%2s".format(FieldFormatter.aliveCellSymbol) else "%2s".format(FieldFormatter.deadCellSymbol))
      )
      .zipWithIndex
      .map { case (r, i) => "%2s".format((i + 1).toString) +: r }
    (header +: withRowN)
      .map(_.mkString(" "))
      .mkString("\n")
  }

  override def equals(obj: Any): Boolean =
    obj match {
      case u: Universe => u.size == size && u.rows == rows
      case _           => false
    }

  def population: Long = rows.foldLeft(0L)((acc, row) => acc + row.size)

  def computeNextIteration(leftSide: Side = ArraySeq.empty, rightSide: Side = ArraySeq.empty): Universe = {
    val universeCopy = rows.map(_.to(mutable.BitSet))
    val cache        = ArraySeq.fill(rows.size)(mutable.BitSet.empty)
    val population   = new AtomicInteger(0)

    rows.zipWithIndex.foreach { case (row, r) =>
//      if (r % 1000 == 0) println(s"$r rows passed...")
      row.foreach { c =>
        val deadNeighbors = neighborsOf(r, c, leftSide, rightSide).filter(l => (l >>> 63) == 0)

        val aliveSize = 8 - deadNeighbors.size
        if (aliveSize == 2 || aliveSize == 3)
          population.incrementAndGet()
        else
          universeCopy(r) -= c

        deadNeighbors
          .filter(l => ((l >>> 31) & 1) == 0) // Filter out all cells from other universes
          .foreach { l =>
            val rr = (l >>> 32).toInt & Int.MaxValue
            val cc = l.toInt & Int.MaxValue

            if (!cache(rr)(cc)) {
              if (neighborsOfB(rr, cc, leftSide, rightSide) == 3) {
                universeCopy(rr) += cc
                population.incrementAndGet()
              }
              cache(rr) += cc
            }

          }
      }
    }

    new Universe(universeCopy.map(_.toImmutable), size, standAlone)
  }

  private[this] def neighborsOfB(r: Int, c: Int, leftSide: Side, rightSide: Side): Int = {
    var alive = 0

    if (getNeighborB(r - 1, c))
      alive += 1
    if (getNeighborB(r + 1, c))
      alive += 1
    if (getNeighborB(r, c + 1, rightSide))
      alive += 1
    if (getNeighborB(r, c - 1, leftSide))
      alive += 1
    if (alive == 4)
      return alive

    if (getNeighborB(r - 1, c + 1, rightSide)) {
      alive += 1

      if (alive == 4)
        return alive
    }

    if (getNeighborB(r - 1, c - 1, leftSide)) {
      alive += 1

      if (alive == 4)
        return alive
    }

    if (getNeighborB(r + 1, c + 1, rightSide)) {
      alive += 1

      if (alive == 4)
        return alive
    }

    if (getNeighborB(r + 1, c - 1, leftSide))
      alive += 1

    alive
  }

  private[this] def neighborsOf(r: Int, c: Int, leftSide: Side, rightSide: Side): Seq[Long] =
    Seq(
      getNeighbor(r - 1, c),                // top
      getNeighbor(r + 1, c),                // bottom
      getNeighbor(r, c + 1, rightSide),     // right
      getNeighbor(r, c - 1, leftSide),      // left
      getNeighbor(r - 1, c + 1, rightSide), // top-right
      getNeighbor(r - 1, c - 1, leftSide),  // top-left
      getNeighbor(r + 1, c + 1, rightSide), // bottom-right
      getNeighbor(r + 1, c - 1, leftSide)   // bottom-left
    )

  private[this] def getNeighbor(r: Int, c: Int, neighborSide: Side = ArraySeq.empty): Long =
    (r >= 0 && r < size.height, c >= 0 && c < size.width) match {
      case (true, true)  => bitMagic(r, c, rows(r)(c))
      case (false, true) => if (r >= size.height) bitMagic(0, c, rows.head(c)) else bitMagic(size.height - 1, c, rows.last(c))

      // Cases in multi-worker mode
      case (true, false) if !standAlone  => magicForUnknown(neighborSide(r))
      case (false, false) if !standAlone => magicForUnknown(neighborSide.head)

      // Cases in stand-alone mode
      case (true, false) =>
        if (c >= size.width) bitMagic(r, 0, rows(r)(0)) else bitMagic(r, size.width - 1, rows(r)(size.width - 1))
      case _             =>
        if (r >= size.height && c >= size.width) bitMagic(0, 0, rows.head(0))
        else if (r < size.height && c >= size.width) bitMagic(size.height - 1, 0, rows.last(0))
        else if (r >= size.height && c < size.width) bitMagic(0, size.width - 1, rows.head(size.width - 1))
        else bitMagic(size.height - 1, size.width - 1, rows.last(size.width - 1))
    }

  private[this] def getNeighborB(r: Int, c: Int, neighborSide: Side = ArraySeq.empty): Boolean =
    (r >= 0 && r < size.height, c >= 0 && c < size.width) match {
      case (true, true)  => rows(r)(c)
      case (false, true) => if (r >= size.height) rows.head(c) else rows.last(c)

      // Cases in multi-worker mode
      case (true, false) if !standAlone  => neighborSide(r)
      case (false, false) if !standAlone => neighborSide.head

      // Cases in stand-alone mode
      case (true, false) =>
        if (c >= size.width) rows(r)(0) else rows(r)(size.width - 1)
      case _             =>
        if (r >= size.height && c >= size.width) rows.head(0)
        else if (r < size.height && c >= size.width) rows.last(0)
        else if (r >= size.height && c < size.width) rows.head(size.width - 1)
        else rows.last(size.width - 1)
    }

  @inline private[this] def bitMagic(r: Int, c: Int, alive: Boolean): Long =
    if (alive)
      ((r.toLong << 32) | (c & Long.MaxValue)) | Long.MinValue
    else
      (r.toLong << 32) | (c & Long.MaxValue)

  @inline private[this] def magicForUnknown(alive: Boolean): Long = if (alive) 0 | Long.MinValue | (1L << 31) else 0 | (1L << 31)
}
