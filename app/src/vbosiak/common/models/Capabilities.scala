package vbosiak.common.models

import play.api.libs.json.{Format, Json}

final case class Capabilities(availableMemory: Long) extends CborSerializable

object Capabilities {
  implicit val format: Format[Capabilities] = Json.format[Capabilities]
}
