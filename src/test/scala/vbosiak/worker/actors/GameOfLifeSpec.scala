package vbosiak.worker.actors

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import vbosiak.TestHelper
import vbosiak.common.utils.FieldFormatter._
import vbosiak.master.controllers.models.Size
import vbosiak.worker.actors.Worker.{Field, Side}
import vbosiak.worker.helpers.{GameOfLife, Universe}

import scala.collection.immutable.{ArraySeq, SortedMap}
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag
import scala.util.Random

final class GameOfLifeSpec extends AnyWordSpecLike with Matchers with TestHelper {
  private def withDetails[T](initial: Field, computed: Field, expected: Field, prefix: String = "")(fun: => T): T =
    withClue(s"$prefix Initial:\n${initial.beautify}\nExpected:\n${expected.beautify}\nComputed:\n${computed.beautify}")(fun)

  private def withDetailsU[T](initial: Field, computed: Universe, expected: Field, prefix: String = "")(fun: => T): T =
    withClue(s"$prefix Initial:\n${initial.beautify}\nExpected:\n${expected.beautify}\nComputed:\n${computed.beautify}")(fun)

  "Worker" when {
    "working with neighbors" must {
      "compute next iteration correctly" in {
        TestCases.nextIterationCases.foreach { case (name, tCase) =>
          info(name)

          val computed = Await.result(GameOfLife.computeNextIteration(tCase.initial, tCase.left, tCase.right), 5.seconds)._1

          withDetails(tCase.initial, computed, tCase.expected) {
            computed mustEqual tCase.expected
          }
        }
      }
    }

    "working in stand-alone mode" must {
      "compute 5 iterations correctly (stand-alone)" in {
        TestCases.next5IterationsStandAloneCases.foreach { case (name, tCase) =>
          info(name)

          (0 until 5).foreach { i =>
            val computed =
              Await.result(GameOfLife.computeNextIteration(tCase.next5(i), ArraySeq.empty, ArraySeq.empty, standAlone = true), 5.seconds)._1

            withDetails(tCase.next5(i), computed, tCase.next5(i + 1), s"[$i]") {
              computed mustEqual tCase.next5(i + 1)
            }
          }
        }
      }

      "creating a field" must {
        val size       = Size(10000, 10000)
        val lifeFactor = 0.2f
        Random.setSeed(1L)
        s"create a field (${size.pretty}) with adequate timing" in {
          val (_, averageDuration) = averageDurationOf(5) {
            ArraySeq.tabulate(size.height, size.width)((_, _) => Random.between(0f, 1f) <= lifeFactor)
          }

          info(s"Average creation duration: ${averageDuration}s")
        }
      }

      "compute 10 000 x 10 000 field with adequate timing" in {
        val size       = Size(10000, 10000)
        val lifeFactor = 0.2f
        Random.setSeed(1L)
        val field      = ArraySeq.tabulate(size.height, size.width)((_, _) => Random.between(0f, 1f) <= lifeFactor)

        val (_, averageDuration) =
          averageDurationOf(5) {
            Await.result(GameOfLife.computeNextIteration(field, ArraySeq.empty, ArraySeq.empty, standAlone = true), 100.seconds)._1
          }

        info(s"Average computing duration: ${averageDuration}s")
      }
    }
  }

  "Universe" when {
    val size       = Size(10000, 10000)
    val lifeFactor = 0.2f
    val seed       = Some(1L)

    "creating a universe" must {
      s"create a universe (${size.pretty}) with adequate timing" in {
        val (r, averageDuration) = averageDurationOf(5) {
          Universe(size, lifeFactor, standAlone = true, seed)
        }

        info(s"Average creation duration: ${averageDuration}s")
      }
    }

    "working with universe" must {
      "compute next iteration successfully" in {
        TestCases.nextIterationCases.foreach { case (name, tCase) =>
          info(name)
          val universe = tCase.initial.toUniverse(standAlone = false)
          val computed = universe.computeNextIteration(tCase.left, tCase.right)

          withDetailsU(tCase.initial, computed, tCase.expected) {
            computed mustEqual tCase.expected.toUniverse(standAlone = false)
          }
        }
      }

      "compute 5 iterations correctly (stand-alone)" in {
        TestCases.next5IterationsStandAloneCases.foreach { case (name, tCase) =>
          info(name)

          (0 until 5).foreach { i =>
            val universe = tCase.next5(i).toUniverse(standAlone = true)
            val computed = universe.computeNextIteration(ArraySeq.empty, ArraySeq.empty)

            withDetailsU(tCase.next5(i), computed, tCase.next5(i + 1), s"[$i]") {
              computed mustEqual tCase.next5(i + 1).toUniverse(standAlone = true)
            }
          }
        }
      }

      s"compute next iteration for ${size.pretty} universe with adequate timing (stand-alone)" in {
        val u                    = Universe(size, lifeFactor, standAlone = true, seed)
        val (_, averageDuration) = averageDurationOf(5) {
          u.computeNextIteration(ArraySeq.empty, ArraySeq.empty)
        }

        info(s"Average computing duration: ${averageDuration}s")
      }
    }
  }
}

object TestCases {
  final case class NextIterationCase(initial: Field, expected: Field, left: Side, right: Side)
  final case class Next5IterationsStandAloneCase(next5: List[Field])

  private val - = false
  private val o = true

  private def A[T: ClassTag](elems: T*) = ArraySeq[T](elems: _*)
  private def S[T: ClassTag](elems: T*) = ArraySeq[T](elems: _*)

  val nextIterationCases: SortedMap[String, NextIterationCase] = SortedMap(
    "5x5, blinker test"                                -> {
      val initial = A(
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(-, o, o, o, -),
        A(-, -, -, -, -),
        A(-, -, -, -, -)
      )
      val next    = A(
        A(-, -, -, -, -),
        A(-, -, o, -, -),
        A(-, -, o, -, -),
        A(-, -, o, -, -),
        A(-, -, -, -, -)
      )
      val left    = S(-, -, -, -, -)
      val right   = S(-, -, -, -, -)

      NextIterationCase(initial, next, left, right)
    },
    "5x5, block, horizontal closure test"              -> {
      val initial = A(
        A(-, -, o, o, -),
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(-, -, o, o, -)
      )
      val next    = A(
        A(-, -, o, o, -),
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(-, -, o, o, -)
      )
      val left    = S(-, -, -, -, -)
      val right   = S(-, -, -, -, -)

      NextIterationCase(initial, next, left, right)
    },
    "5x5, blocks, vertical closure test"               -> {
      val initial = A(
        A(-, -, -, -, -),
        A(o, -, -, -, o),
        A(o, -, -, -, o),
        A(-, -, -, -, -),
        A(-, -, -, -, -)
      )
      val next    = A(
        A(-, -, -, -, -),
        A(o, -, -, -, o),
        A(o, -, -, -, o),
        A(-, -, -, -, -),
        A(-, -, -, -, -)
      )
      val left    = S(-, o, o, -, -)
      val right   = S(-, o, o, -, -)

      NextIterationCase(initial, next, left, right)
    },
    "5x5, blinker, vertical & horizontal closure test" -> {
      val initial = A(
        A(o, -, -, -, -),
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(o, -, -, -, -),
        A(o, -, -, -, -)
      )
      val next    = A(
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(o, o, -, -, -)
      )
      val left    = S(-, -, -, -, -)
      val right   = S(-, -, -, -, -)

      NextIterationCase(initial, next, left, right)
    },
    "5x5, glider test"                                 -> {
      val initial = A(
        A(-, -, -, -, -),
        A(-, -, o, -, -),
        A(-, -, -, o, -),
        A(-, o, o, o, -),
        A(-, -, -, -, -)
      )
      val next    = A(
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(-, o, -, o, -),
        A(-, -, o, o, -),
        A(-, -, o, -, -)
      )
      val left    = S(-, -, -, -, -)
      val right   = S(-, -, -, -, -)

      NextIterationCase(initial, next, left, right)
    },
    "5x5, block, vertical & horizontal closure test"   -> {
      val initial = A(
        A(o, -, -, -, -),
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(o, -, -, -, -)
      )
      val next    = A(
        A(o, -, -, -, -),
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(o, -, -, -, -)
      )
      val left    = S(o, -, -, -, o)
      val right   = S(-, -, -, -, -)

      NextIterationCase(initial, next, left, right)
    }
  )

  val next5IterationsStandAloneCases: SortedMap[String, Next5IterationsStandAloneCase] = SortedMap(
    "5x5, glider test"                                               -> {
      val iterations = List(
        A(
          A(-, -, -, -, -),
          A(-, -, -, -, -),
          A(-, -, -, o, -),
          A(-, -, -, -, o),
          A(-, -, o, o, o)
        ),
        A(
          A(-, -, -, o, -),
          A(-, -, -, -, -),
          A(-, -, -, -, -),
          A(-, -, o, -, o),
          A(-, -, -, o, o)
        ),
        A(
          A(-, -, -, o, o),
          A(-, -, -, -, -),
          A(-, -, -, -, -),
          A(-, -, -, -, o),
          A(-, -, o, -, o)
        ),
        A(
          A(-, -, -, o, o),
          A(-, -, -, -, -),
          A(-, -, -, -, -),
          A(-, -, -, o, -),
          A(o, -, -, -, o)
        ),
        A(
          A(o, -, -, o, o),
          A(-, -, -, -, -),
          A(-, -, -, -, -),
          A(-, -, -, -, o),
          A(o, -, -, -, -)
        ),
        A(
          A(o, -, -, -, o),
          A(-, -, -, -, o),
          A(-, -, -, -, -),
          A(-, -, -, -, -),
          A(o, -, -, o, -)
        )
      )
      Next5IterationsStandAloneCase(iterations)
    },
    "10x10, variable structures, vertical & horizontal closure test" -> {
      val firstTwo = List(
        A(
          A(o, -, -, -, -, o, -, -, -, o),
          A(-, -, -, -, -, o, -, -, -, -),
          A(-, -, -, -, -, -, -, -, -, -),
          A(-, -, -, -, -, -, -, -, -, -),
          A(o, -, -, -, -, -, -, -, -, o),
          A(o, -, -, o, o, o, -, -, -, o),
          A(-, -, -, -, o, o, o, -, -, -),
          A(-, -, -, -, -, -, -, -, -, -),
          A(-, -, -, -, -, -, -, -, -, -),
          A(o, -, -, -, -, o, -, -, -, o)
        ),
        A(
          A(o, -, -, -, o, o, o, -, -, o),
          A(-, -, -, -, -, -, -, -, -, -),
          A(-, -, -, -, -, -, -, -, -, -),
          A(-, -, -, -, -, -, -, -, -, -),
          A(o, -, -, -, o, -, -, -, -, o),
          A(o, -, -, o, -, -, o, -, -, o),
          A(-, -, -, o, -, -, o, -, -, -),
          A(-, -, -, -, -, o, -, -, -, -),
          A(-, -, -, -, -, -, -, -, -, -),
          A(o, -, -, -, -, -, -, -, -, o)
        )
      )

      val iterations = firstTwo ++ firstTwo ++ firstTwo

      Next5IterationsStandAloneCase(iterations)
    }
  )
}
