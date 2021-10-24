package vbosiak.worker.actors

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import vbosiak.common.utils.FieldFormatter._
import vbosiak.worker.actors.Worker.Field

import scala.collection.immutable.SortedMap

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
  final case class NextIterationCase(initial: Field, expected: Field, left: Vector[Boolean], right: Vector[Boolean])
  final case class Next5IterationsStandAloneCase(next5: List[(Field, Vector[Boolean], Vector[Boolean])])

  private val - = false
  private val o = true

  private def V[T](elems: T*) = Vector[T](elems: _*)

  val nextIterationCases: SortedMap[String, NextIterationCase] = SortedMap(
    "5x5, blinker test"                                -> {
      val initial = V(
        V(-, -, -, -, -),
        V(-, -, -, -, -),
        V(-, o, o, o, -),
        V(-, -, -, -, -),
        V(-, -, -, -, -)
      )
      val next    = V(
        V(-, -, -, -, -),
        V(-, -, o, -, -),
        V(-, -, o, -, -),
        V(-, -, o, -, -),
        V(-, -, -, -, -)
      )
      val left    = V(-, -, -, -, -)
      val right   = V(-, -, -, -, -)

      NextIterationCase(initial, next, left, right)
    },
    "5x5, block, horizontal closure test"              -> {
      val initial = V(
        V(-, -, o, o, -),
        V(-, -, -, -, -),
        V(-, -, -, -, -),
        V(-, -, -, -, -),
        V(-, -, o, o, -)
      )
      val next    = V(
        V(-, -, o, o, -),
        V(-, -, -, -, -),
        V(-, -, -, -, -),
        V(-, -, -, -, -),
        V(-, -, o, o, -)
      )
      val left    = V(-, -, -, -, -)
      val right   = V(-, -, -, -, -)

      NextIterationCase(initial, next, left, right)
    },
    "5x5, blocks, vertical closure test"               -> {
      val initial = V(
        V(-, -, -, -, -),
        V(o, -, -, -, o),
        V(o, -, -, -, o),
        V(-, -, -, -, -),
        V(-, -, -, -, -)
      )
      val next    = V(
        V(-, -, -, -, -),
        V(o, -, -, -, o),
        V(o, -, -, -, o),
        V(-, -, -, -, -),
        V(-, -, -, -, -)
      )
      val left    = V(-, o, o, -, -)
      val right   = V(-, o, o, -, -)

      NextIterationCase(initial, next, left, right)
    },
    "5x5, blinker, vertical & horizontal closure test" -> {
      val initial = V(
        V(o, -, -, -, -),
        V(-, -, -, -, -),
        V(-, -, -, -, -),
        V(o, -, -, -, -),
        V(o, -, -, -, -)
      )
      val next    = V(
        V(-, -, -, -, -),
        V(-, -, -, -, -),
        V(-, -, -, -, -),
        V(-, -, -, -, -),
        V(o, o, -, -, -)
      )
      val left    = V(-, -, -, -, -)
      val right   = V(-, -, -, -, -)

      NextIterationCase(initial, next, left, right)
    },
    "5x5, glider test"                                 -> {
      val initial = V(
        V(-, -, -, -, -),
        V(-, -, o, -, -),
        V(-, -, -, o, -),
        V(-, o, o, o, -),
        V(-, -, -, -, -)
      )
      val next    = V(
        V(-, -, -, -, -),
        V(-, -, -, -, -),
        V(-, o, -, o, -),
        V(-, -, o, o, -),
        V(-, -, o, -, -)
      )
      val left    = V(-, -, -, -, -)
      val right   = V(-, -, -, -, -)

      NextIterationCase(initial, next, left, right)
    },
    "5x5, block, vertical & horizontal closure test"   -> {
      val initial = V(
        V(o, -, -, -, -),
        V(-, -, -, -, -),
        V(-, -, -, -, -),
        V(-, -, -, -, -),
        V(o, -, -, -, -)
      )
      val next    = V(
        V(o, -, -, -, -),
        V(-, -, -, -, -),
        V(-, -, -, -, -),
        V(-, -, -, -, -),
        V(o, -, -, -, -)
      )
      val left    = V(o, -, -, -, o)
      val right   = V(-, -, -, -, -)

      NextIterationCase(initial, next, left, right)
    }
  )

  val next5IterationsStandAloneCases: SortedMap[String, Next5IterationsStandAloneCase] = SortedMap(
    "5x5, glider test"                                               -> {
      val iterations: List[(Field, Vector[Boolean], Vector[Boolean])] = List(
        standLoneField(
          V(-, -, -, -, -),
          V(-, -, -, -, -),
          V(-, -, -, o, -),
          V(-, -, -, -, o),
          V(-, -, o, o, o)
        ),
        standLoneField(
          V(-, -, -, o, -),
          V(-, -, -, -, -),
          V(-, -, -, -, -),
          V(-, -, o, -, o),
          V(-, -, -, o, o)
        ),
        standLoneField(
          V(-, -, -, o, o),
          V(-, -, -, -, -),
          V(-, -, -, -, -),
          V(-, -, -, -, o),
          V(-, -, o, -, o)
        ),
        standLoneField(
          V(-, -, -, o, o),
          V(-, -, -, -, -),
          V(-, -, -, -, -),
          V(-, -, -, o, -),
          V(o, -, -, -, o)
        ),
        standLoneField(
          V(o, -, -, o, o),
          V(-, -, -, -, -),
          V(-, -, -, -, -),
          V(-, -, -, -, o),
          V(o, -, -, -, -)
        ),
        standLoneField(
          V(o, -, -, -, o),
          V(-, -, -, -, o),
          V(-, -, -, -, -),
          V(-, -, -, -, -),
          V(o, -, -, o, -)
        )
      )
      Next5IterationsStandAloneCase(iterations)
    },
    "10x10, variable structures, vertical & horizontal closure test" -> {
      val firstTwo: List[(Field, Vector[Boolean], Vector[Boolean])] = List(
        standLoneField(
          V(o, -, -, -, -, o, -, -, -, o),
          V(-, -, -, -, -, o, -, -, -, -),
          V(-, -, -, -, -, -, -, -, -, -),
          V(-, -, -, -, -, -, -, -, -, -),
          V(o, -, -, -, -, -, -, -, -, o),
          V(o, -, -, o, o, o, -, -, -, o),
          V(-, -, -, -, o, o, o, -, -, -),
          V(-, -, -, -, -, -, -, -, -, -),
          V(-, -, -, -, -, -, -, -, -, -),
          V(o, -, -, -, -, o, -, -, -, o)
        ),
        standLoneField(
          V(o, -, -, -, o, o, o, -, -, o),
          V(-, -, -, -, -, -, -, -, -, -),
          V(-, -, -, -, -, -, -, -, -, -),
          V(-, -, -, -, -, -, -, -, -, -),
          V(o, -, -, -, o, -, -, -, -, o),
          V(o, -, -, o, -, -, o, -, -, o),
          V(-, -, -, o, -, -, o, -, -, -),
          V(-, -, -, -, -, o, -, -, -, -),
          V(-, -, -, -, -, -, -, -, -, -),
          V(o, -, -, -, -, -, -, -, -, o)
        )
      )

      val iterations = firstTwo ++ firstTwo ++ firstTwo

      Next5IterationsStandAloneCase(iterations)
    }
  )

  private def standLoneField(fieldRows: Vector[Boolean]*): (Field, Vector[Boolean], Vector[Boolean]) = {
    val matrix = V(fieldRows: _*)

    (matrix, matrix.map(_.last), matrix.map(_.head))
  }
}
