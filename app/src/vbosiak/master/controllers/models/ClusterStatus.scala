package vbosiak.master.controllers.models

import enumeratum._

private[master] sealed trait ClusterStatus extends EnumEntry

private[master] object ClusterStatus extends Enum[ClusterStatus] with PlayJsonEnum[ClusterStatus] {
  override val values: IndexedSeq[ClusterStatus] = findValues

  case object Idle    extends ClusterStatus
  case object Running extends ClusterStatus
}
