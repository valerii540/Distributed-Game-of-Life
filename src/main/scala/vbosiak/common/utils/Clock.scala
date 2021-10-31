package vbosiak.common.utils

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

object Clock {
  def withMeasuring[T](f: => T): (T, FiniteDuration) = {
    val tick   = System.nanoTime()
    val result = f
    result -> FiniteDuration(System.nanoTime() - tick, TimeUnit.NANOSECONDS)
  }

  def fromNano(duration: Long): FiniteDuration = FiniteDuration(duration, TimeUnit.NANOSECONDS)
}
