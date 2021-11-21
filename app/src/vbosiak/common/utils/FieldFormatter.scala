package vbosiak.common.utils

import vbosiak.worker.actors.Worker.{Field, Side}

import scala.collection.immutable.ArraySeq

object FieldFormatter {
  private val deadCellSymbol  = "-"
  private val aliveCellSymbol = Console.GREEN + " o" + Console.RESET

  implicit class FieldExtensions(field: Field) {
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

  implicit class SideExtensions(side: Side) {
    def beautify: String =
      side.map(alive => if (alive) "%2s".format(aliveCellSymbol) else "%2s".format(deadCellSymbol)).mkString(", ")
  }
}
