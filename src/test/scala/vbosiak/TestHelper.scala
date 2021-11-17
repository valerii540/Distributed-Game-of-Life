package vbosiak

import vbosiak.common.utils.Clock

trait TestHelper {
  def averageDurationOf[T](attempts: Int)(f: => T): (T, Float) = {
    val results = (0 until attempts + 1).map { _ =>
      val (res, duration) = Clock.withMeasuring {
        f
      }
      res -> duration
    }

    val sum = results.tail
      .foldLeft(0L)((acc, res) => acc + res._2.toMillis)

    results.head._1 -> (sum / attempts / 1000f)
  }
}
