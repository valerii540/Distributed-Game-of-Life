package vbosiak.common.utils

import vbosiak.worker.actors.Worker.Field

import scala.collection.immutable.ArraySeq

object FieldFormatter {
  implicit class FieldExtensions(field: Field) {
    private val deadCellSymbol  = "-"
    private val aliveCellSymbol = Console.GREEN + " o" + Console.RESET

    def beautify: String = {
      val header: ArraySeq[String] = ArraySeq("  " +: Seq.tabulate(field.head.size)(i => "%2s".format((i + 1).toString)): _*)
      val withRowN                 = field
        .map(_.map(alive => if (alive) "%2s".format(aliveCellSymbol) else "%2s".format(deadCellSymbol)))
        .zipWithIndex
        .map { case (r, i) => "%2s".format((i + 1).toString) +: r }
      (header +: withRowN)
        .map(_.mkString(" "))
        .mkString("\n")
    }
  }
}
