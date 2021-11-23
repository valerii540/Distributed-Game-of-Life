package vbosiak.worker.actors

import akka.actor.typed.scaladsl.ActorContext
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import vbosiak.common.utils.FieldFormatter._
import vbosiak.worker.actors.Worker.{Field, Side, WorkerCommand}
import vbosiak.worker.helpers.WorkerHelper

import scala.collection.immutable.{ArraySeq, SortedMap}
import scala.reflect.ClassTag

final class GameOfLifeSpec extends AnyWordSpecLike with Matchers {
  private val workerHelper = new WorkerHelper {
    // game of life computing does not require actor context
    override implicit val context: ActorContext[WorkerCommand] = null
  }

  private def withDetails[T](initial: Field, computed: Field, expected: Field, sides: Option[(Side, Side)] = None, prefix: String = "")(
      fun: => T
  ): T =
    withClue(s"$prefix Initial:\n${initial.beautify}\nExpected:\n${expected.beautify}\nComputed:\n${computed.beautify}\nSides: ${sides
      .map(ss => s"${ss._1.beautify} | ${ss._2.beautify}")}\n")(fun)

  "Worker" when {
    "working with neighbors" must {
      "compute next iteration for 5x5, blinker test correctly" in {
        val tCase = TestCases.nextIterationCases("5x5, blinker test")

        val computed    = workerHelper.computeNextIteration(tCase.initial, tCase.left, tCase.right, inParallel = false)._1
        val computedPar = workerHelper.computeNextIteration(tCase.initial, tCase.left, tCase.right)._1

        withDetails(tCase.initial, computed, tCase.expected, Some(tCase.left, tCase.right), "[single thread]") {
          computed mustEqual tCase.expected
        }
        withDetails(tCase.initial, computed, tCase.expected, Some(tCase.left, tCase.right), "[in parallel]") {
          computedPar mustEqual tCase.expected
        }
      }

      "compute next iteration for 5x5, block, horizontal closure test correctly" in {
        val tCase = TestCases.nextIterationCases("5x5, block, horizontal closure test")

        val computed    = workerHelper.computeNextIteration(tCase.initial, tCase.left, tCase.right, inParallel = false)._1
        val computedPar = workerHelper.computeNextIteration(tCase.initial, tCase.left, tCase.right)._1

        withDetails(tCase.initial, computed, tCase.expected, Some(tCase.left, tCase.right), "[single thread]") {
          computed mustEqual tCase.expected
        }
        withDetails(tCase.initial, computed, tCase.expected, Some(tCase.left, tCase.right), "[in parallel]") {
          computedPar mustEqual tCase.expected
        }
      }

      "compute next iteration for 5x5, blocks, vertical closure test correctly" in {
        val tCase = TestCases.nextIterationCases("5x5, blocks, vertical closure test")

        val computed    = workerHelper.computeNextIteration(tCase.initial, tCase.left, tCase.right, inParallel = false)._1
        val computedPar = workerHelper.computeNextIteration(tCase.initial, tCase.left, tCase.right)._1

        withDetails(tCase.initial, computed, tCase.expected, Some(tCase.left, tCase.right), "[single thread]") {
          computed mustEqual tCase.expected
        }
        withDetails(tCase.initial, computed, tCase.expected, Some(tCase.left, tCase.right), "[in parallel]") {
          computedPar mustEqual tCase.expected
        }
      }

      "compute next iteration for 5x5, blinker, vertical & horizontal closure test correctly" in {
        val tCase = TestCases.nextIterationCases("5x5, blinker, vertical & horizontal closure test")

        val computed    = workerHelper.computeNextIteration(tCase.initial, tCase.left, tCase.right, inParallel = false)._1
        val computedPar = workerHelper.computeNextIteration(tCase.initial, tCase.left, tCase.right)._1

        withDetails(tCase.initial, computed, tCase.expected, Some(tCase.left, tCase.right), "[single thread]") {
          computed mustEqual tCase.expected
        }
        withDetails(tCase.initial, computed, tCase.expected, Some(tCase.left, tCase.right), "[in parallel]") {
          computedPar mustEqual tCase.expected
        }
      }

      "compute next iteration for 5x5, glider test correctly" in {
        val tCase = TestCases.nextIterationCases("5x5, glider test")

        val computed    = workerHelper.computeNextIteration(tCase.initial, tCase.left, tCase.right, inParallel = false)._1
        val computedPar = workerHelper.computeNextIteration(tCase.initial, tCase.left, tCase.right)._1

        withDetails(tCase.initial, computed, tCase.expected, Some(tCase.left, tCase.right), "[single thread]") {
          computed mustEqual tCase.expected
        }
        withDetails(tCase.initial, computed, tCase.expected, Some(tCase.left, tCase.right), "[in parallel]") {
          computedPar mustEqual tCase.expected
        }
      }

      "compute next iteration for 5x5, block, vertical & horizontal closure test correctly" in {
        val tCase = TestCases.nextIterationCases("5x5, block, vertical & horizontal closure test")

        val computed    = workerHelper.computeNextIteration(tCase.initial, tCase.left, tCase.right, inParallel = false)._1
        val computedPar = workerHelper.computeNextIteration(tCase.initial, tCase.left, tCase.right)._1

        withDetails(tCase.initial, computed, tCase.expected, Some(tCase.left, tCase.right), "[single thread]") {
          computed mustEqual tCase.expected
        }
        withDetails(tCase.initial, computed, tCase.expected, Some(tCase.left, tCase.right), "[in parallel]") {
          computedPar mustEqual tCase.expected
        }
      }
    }

    "working in stand-alone mode" must {
      "compute 5 iterations for 5x5, glider test correctly" in {
        val tCase = TestCases.next5IterationsStandAloneCases("5x5, glider test")

        (0 until 5).foreach { i =>
          val computed    = workerHelper.computeNextIteration(tCase.next5(i), ArraySeq.empty, ArraySeq.empty, standAlone = true, inParallel = false)._1
          val computedPar = workerHelper.computeNextIteration(tCase.next5(i), ArraySeq.empty, ArraySeq.empty, standAlone = true)._1

          withDetails(tCase.next5(i), computed, tCase.next5(i + 1), None, s"[$i, single thread]") {
            computed mustEqual tCase.next5(i + 1)
          }
          withDetails(tCase.next5(i), computed, tCase.next5(i + 1), None, s"[$i, in parallel]") {
            computedPar mustEqual tCase.next5(i + 1)
          }
        }
      }

      "compute 5 iterations for 10x10, variable structures, vertical & horizontal closure test correctly" in {
        val tCase = TestCases.next5IterationsStandAloneCases("10x10, variable structures, vertical & horizontal closure test")

        (0 until 5).foreach { i =>
          val computed    = workerHelper.computeNextIteration(tCase.next5(i), ArraySeq.empty, ArraySeq.empty, standAlone = true, inParallel = false)._1
          val computedPar = workerHelper.computeNextIteration(tCase.next5(i), ArraySeq.empty, ArraySeq.empty, standAlone = true)._1

          withDetails(tCase.next5(i), computed, tCase.next5(i + 1), None, s"[$i, single thread]") {
            computed mustEqual tCase.next5(i + 1)
          }
          withDetails(tCase.next5(i), computed, tCase.next5(i + 1), None, s"[$i, in parallel]") {
            computedPar mustEqual tCase.next5(i + 1)
          }
        }
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
        A(o, -, -, -, o),
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(o, -, -, -, o)
      )
      val next    = A(
        A(o, -, -, -, o),
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(-, -, -, -, -),
        A(o, -, -, -, o)
      )
      val left    = S(o, -, -, -, o)
      val right   = S(o, -, -, -, o)

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
