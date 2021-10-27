package vbosiak.master.models

import play.api.libs.json.{Format, Json}

final case class UserParameters(mode: Mode, delay: Option[Long], lifeFactor: Float)

object UserParameters {
  implicit val format: Format[UserParameters] = Json.format[UserParameters]
}
