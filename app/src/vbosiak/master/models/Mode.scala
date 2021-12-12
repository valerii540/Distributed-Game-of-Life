package vbosiak.master.models

import enumeratum._

sealed trait Mode extends EnumEntry

object Mode extends Enum[Mode] with PlayInsensitiveJsonEnum[Mode] {
  override val values: IndexedSeq[Mode] = findValues

  case object Manual    extends Mode
  case object Fastest   extends Mode
}
