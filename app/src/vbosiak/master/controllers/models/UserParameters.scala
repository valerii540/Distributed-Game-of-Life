package vbosiak.master.controllers.models

import play.api.libs.json.{Format, Json}
import vbosiak.common.models.CborSerializable
import vbosiak.master.models.Mode

final case class UserParameters(
    mode: Mode,
    lifeFactor: Float,
    forceDistribution: Boolean,
    writeLogFile: Boolean,
    preferredFieldSize: Option[Size],
    seed: Option[Int]
)

final case class Size(height: Int, width: Int) extends CborSerializable {
  def area: Long     = height * width.toLong
  def pretty: String = s"${height}x$width"
}

object Size {
  def apply(squareSideLength: Int): Size = Size(squareSideLength, squareSideLength)
}

object UserParameters {
  implicit val preferredSizeFormat: Format[Size] = Json.format[Size]
  implicit val format: Format[UserParameters]    = Json.format[UserParameters]
}
