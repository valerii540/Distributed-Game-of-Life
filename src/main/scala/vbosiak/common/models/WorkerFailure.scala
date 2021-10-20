package vbosiak.common.models

sealed trait WorkerFailure

final case class CriticalError(throwable: Throwable) extends WorkerFailure
