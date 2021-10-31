package vbosiak.master.models

import play.api.libs.json.{Format, Json}

final case class UserParameters(
    mode: Mode,
    delay: Option[Long],
    lifeFactor: Float,
    forceDistribution: Boolean,
    preferredFieldSize: Option[PreferredSize]
)

final case class PreferredSize(height: Int, width: Int)

object UserParameters {
  implicit val preferredSizeFormat: Format[PreferredSize] = Json.format[PreferredSize]
  implicit val format: Format[UserParameters]             = Json.format[UserParameters]
}
