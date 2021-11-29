package vbosiak.worker

import org.scalameter._
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAllConfigMap, ConfigMap}
import vbosiak.master.controllers.models.Size
import vbosiak.worker.helpers.GameOfLife

import scala.collection.compat.immutable.ArraySeq

final class PerformanceTests extends AnyWordSpecLike with Matchers with BeforeAndAfterAllConfigMap {
  private var mode = "fast"

  override def beforeAll(configMap: ConfigMap): Unit =
    configMap.get("perf").foreach(m => mode = m.toString)

  private val lifeFactor = 0.2f
  private val seed       = Some(1L)

  "Matrix implementation" must {
    "compute next iteration with adequate timings (10 000 x 10 000) [single thread]" in {
      if (mode == "none") cancel()

      val size  = Size(10_000, 10_000)
      val field = GameOfLife.createNewField(size, lifeFactor, seed)

      val time = config(
        Key.exec.benchRuns     := 5,
        Key.exec.maxWarmupRuns := 2,
        Key.exec.minWarmupRuns := 2,
        Key.exec.jvmflags      := List("-Xmx4G")
      )
        .withWarmer(new Warmer.Default)
        .withMeasurer(new Measurer.IgnoringGC)
        .measure {
          GameOfLife.computeNextIteration(field, ArraySeq.empty, ArraySeq.empty, standAlone = true, inParallel = false)
        }

      info(s"Total time: ${time.value / 1000f}s")
    }

    "compute next iteration with adequate timings (10 000 x 10 000) [in parallel]" in {
      if (mode == "none") cancel()

      val size  = Size(10_000, 10_000)
      val field = GameOfLife.createNewField(size, lifeFactor, seed)

      val time = config(
        Key.exec.benchRuns     := 5,
        Key.exec.maxWarmupRuns := 2,
        Key.exec.minWarmupRuns := 2,
        Key.exec.jvmflags      := List("-Xmx4G")
      )
        .withWarmer(new Warmer.Default)
        .withMeasurer(new Measurer.IgnoringGC)
        .measure {
          GameOfLife.computeNextIteration(field, ArraySeq.empty, ArraySeq.empty, standAlone = true)
        }

      info(s"Total time: ${time.value / 1000f}s")
    }

    "create 10 000 x 10 000 field with adequate timings" in {
      if (mode == "none") cancel()

      val size = Size(10_000, 10_000)

      val time = config(
        Key.exec.benchRuns     := 5,
        Key.exec.maxWarmupRuns := 2,
        Key.exec.minWarmupRuns := 2,
        Key.exec.jvmflags      := List("-Xmx4G")
      )
        .withWarmer(new Warmer.Default)
        .withMeasurer(new Measurer.IgnoringGC)
        .measure {
          GameOfLife.createNewField(size, lifeFactor, seed)
        }

      info(s"Total time: ${time.value / 1000f}s")
    }

    "compute next iteration with adequate timings (10 000 x 100 000) [single thread]" in {
      if (mode == "none" || mode == "fast") cancel()

      val size  = Size(10_000, 100_000)
      val field = GameOfLife.createNewField(size, lifeFactor, seed)

      val time = config(
        Key.exec.benchRuns     := 2,
        Key.exec.maxWarmupRuns := 1,
        Key.exec.jvmflags      := List("-Xmx4G")
      )
        .withWarmer(new Warmer.Default)
        .withMeasurer(new Measurer.IgnoringGC)
        .measure {
          GameOfLife.computeNextIteration(field, ArraySeq.empty, ArraySeq.empty, standAlone = true, inParallel = false)
        }

      info(s"Total time: ${time.value / 1000f}s")
    }
  }

}
