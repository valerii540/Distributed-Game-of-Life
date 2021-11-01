package vbosiak.common.utils

import scala.collection.immutable.ArraySeq
import scala.reflect.ClassTag

object FieldPainter {
  val - : Boolean = false
  val o: Boolean  = true

  //noinspection TypeAnnotation
  def A[T: ClassTag](elems: T*) = ArraySeq[T](elems: _*)
}
