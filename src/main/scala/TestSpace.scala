import vbosiak.common.utils.Clock
import vbosiak.master.controllers.models.Size
import vbosiak.worker.helpers.{GameOfLife, Universe}

import scala.collection.immutable.ArraySeq
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Random

object TestSpace {
  private val size       = Size(10000, 10000)
  private val lifeFactor = 0.2f
  private val seed       = Some(1L)

  println(s"| Size: ${size.pretty}")
  println(s"| Life factor: $lifeFactor")
  println(s"| Seed: $seed\n\n")

  def averageDurationOf[T](attempts: Int)(f: => T): (T, Float) = {
    val results = (0 until (if (attempts != 1) attempts + 1 else 1)).map { i =>
      println(s"\nATTEMPT #$i")
      val (res, duration) = Clock.withMeasuring {
        f
      }
      res -> duration
    }

    val sum = results.tail
      .foldLeft(0L)((acc, res) => acc + res._2.toMillis)

    results.head._1 -> (sum / attempts / 1000f)
  }

  def durationMeasure[T](f: => T): (T, Float) = {
    val start = System.nanoTime()
    val r     = f
    val stop  = System.nanoTime()
    r -> ((stop - start) / 1e9f)
  }

  def durationPrint[T](s: String)(f: => T): T = {
    val (r, d) = durationMeasure(f)
    println(s"===> $s: ${d}s")
    r
  }

  def bitSet(attempts: Int): Unit = {
    val u = Universe(size, lifeFactor, standAlone = true, seed)
    println("Created")

    val (_, d) =
      averageDurationOf(attempts) {
        durationPrint("Overall") {
          u.computeNextIteration(ArraySeq.empty, ArraySeq.empty)
        }
      }

    println("Dur: " + d)
  }

  def array(attempts: Int): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    seed.foreach(Random.setSeed)
    val field  = ArraySeq.tabulate(size.height, size.width)((_, _) => Random.between(0f, 1f) <= lifeFactor)
    println("Created")

    val (_, d) =
      averageDurationOf(attempts) {
        durationPrint("Overall") {
          Await.result(GameOfLife.computeNextIteration(field, ArraySeq.empty, ArraySeq.empty, standAlone = true), 1000.seconds)._1
        }
      }
    println("Dur: " + d)
  }

  def main(args: Array[String]): Unit = {
    args.head match {
      case "m" =>
        println("Matrix implementation")
        array(args(1).toInt)
      case "b" =>
        println("BitSet implementation")
        bitSet(args(1).toInt)
      case "both" =>
        println("BitSet implementation")
        bitSet(args(1).toInt)
        System.gc()
        println("------------------------")
        println("Matrix implementation")
        array(args(1).toInt)
      case _   => throw new IllegalArgumentException
    }
  }
}
