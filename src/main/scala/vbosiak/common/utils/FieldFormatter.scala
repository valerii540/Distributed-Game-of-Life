package vbosiak.common.utils

import vbosiak.worker.actors.Worker.Field

object FieldFormatter {
  implicit class FieldExtensions(field: Field) {
    private val deadCellSymbol  = '-'
    private val aliveCellSymbol = 'o'

    def beautify: String =
      field.map(_.map(alive => if (alive) aliveCellSymbol else deadCellSymbol).mkString(" ")).mkString("\n")
  }
}
