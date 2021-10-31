package vbosiak.worker.models

import enumeratum._

sealed trait WorkerBehaviour extends EnumEntry

object WorkerBehaviour extends Enum[WorkerBehaviour] {
  override val values: IndexedSeq[WorkerBehaviour] = findValues

  case object Idle                                extends WorkerBehaviour
  final case class Processing(standAlone: Boolean) extends WorkerBehaviour
}
