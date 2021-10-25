package vbosiak.common.models

import play.api.libs.json.{Format, Json}

final case class Capabilities(availableMemory: Long, maxFiledSideSize: Int)

object Capabilities {
  implicit val format: Format[Capabilities] = Json.format[Capabilities]
}
