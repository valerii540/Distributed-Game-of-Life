package vbosiak.worker.actors

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import vbosiak.common.utils.FieldFormatter._
import vbosiak.worker.actors.Worker.Field

import scala.collection.immutable.{ArraySeq, SortedMap}
import scala.reflect.ClassTag

final class WorkerSpec extends AnyWordSpecLike with Matchers {
  private def withDetails[T](initial: Field, computed: Field, expected: Field, prefix: String = "")(fun: => T): T =
    withClue(s"$prefix Initial:\n${initial.beautify}\nExpected:\n${expected.beautify}\nComputed:\n${computed.beautify}")(fun)

  "Worker" when {
    "working with neighbors" must {
      "compute next iteration correctly" in {
        TestCases.nextIterationCases.foreach { case (name, tCase) =>
          info(name)

          val computed = Worker.computeNextIteration(tCase.initial, tCase.left, tCase.right)._1

          withDetails(tCase.initial, computed, tCase.expected) {
            computed mustEqual tCase.expected
          }
        }
      }
    }

    "working in stand-alone mode" must {
      "compute 5 iterations correctly" in {
        TestCases.next5IterationsStandAloneCases.foreach { case (name, tCase) =>
          info(name)

          (0 until 5).foreach { i =>
            val computed = Worker.computeNextIteration(tCase.next5(i)._1, tCase.next5(i)._2, tCase.next5(i)._3)._1

            withDetails(tCase.next5(i)._1, computed, tCase.next5(i + 1)._1, s"[$i]") {
              computed mustEqual tCase.next5(i + 1)._1
            }
          }
        }
      }
    }
  }
}

object TestCases {
  final case class NextIterationCase(initial: Field, expected: Field, left: ArraySeq[Boolean], right: ArraySeq[Boolean])
  final case class Next5IterationsStandAloneCase(next5: List[(Field, ArraySeq[Boolean], ArraySeq[Boolean])])

  private val - = false
  private val o = true

  private def A[T: ClassTag](elems: T*) = ArraySeq[T](elems: _*)

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
      val left    = A(-, -, -, -, -)
      val right   = A(-, -, -, -, -)

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
      val left    = A(-, -, -, -, -)
      val right   = A(-, -, -, -, -)

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
      val left    = A(-, o, o, -, -)
      val right   = A(-, o, o, -, -)

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
      val left    = A(-, -, -, -, -)
      val right   = A(-, -, -, -, -)

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
      val left    = A(-, -, -, -, -)
      val right   = A(-, -, -, -, -)

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
      val left    = A(o, -, -, -, o)
      val right   = A(-, -, -, -, -)

      NextIterationCase(initial, next, left, right)
    }
  )

  val next5IterationsStandAloneCases: SortedMap[String, Next5IterationsStandAloneCase] = SortedMap(
    "5x5, glider test"                                               -> {
      val iterations = List(
        standLoneField(
          A(-, -, -, -, -),
          A(-, -, -, -, -),
          A(-, -, -, o, -),
          A(-, -, -, -, o),
          A(-, -, o, o, o)
        ),
        standLoneField(
          A(-, -, -, o, -),
          A(-, -, -, -, -),
          A(-, -, -, -, -),
          A(-, -, o, -, o),
          A(-, -, -, o, o)
        ),
        standLoneField(
          A(-, -, -, o, o),
          A(-, -, -, -, -),
          A(-, -, -, -, -),
          A(-, -, -, -, o),
          A(-, -, o, -, o)
        ),
        standLoneField(
          A(-, -, -, o, o),
          A(-, -, -, -, -),
          A(-, -, -, -, -),
          A(-, -, -, o, -),
          A(o, -, -, -, o)
        ),
        standLoneField(
          A(o, -, -, o, o),
          A(-, -, -, -, -),
          A(-, -, -, -, -),
          A(-, -, -, -, o),
          A(o, -, -, -, -)
        ),
        standLoneField(
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
        standLoneField(
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
        standLoneField(
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

  private def standLoneField(fieldRows: ArraySeq[Boolean]*): (Field, ArraySeq[Boolean], ArraySeq[Boolean]) = {
    val matrix = A(fieldRows: _*)

    (matrix, matrix.map(_.last), matrix.map(_.head))
  }
}
