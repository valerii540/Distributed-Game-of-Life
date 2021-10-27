package vbosiak.master.models

import play.api.libs.json.{Format, Json}

final case class UserParameters(mode: Mode, delay: Option[Long], lifeFactor: Float, preferredFieldSize: Option[PreferredSize], forceDistribution: Option[Boolean])

final case class PreferredSize(height: Long, width: Long)

object UserParameters {
  implicit val preferredSizeFormat: Format[PreferredSize] = Json.format[PreferredSize]
  implicit val format: Format[UserParameters] = Json.format[UserParameters]
}
