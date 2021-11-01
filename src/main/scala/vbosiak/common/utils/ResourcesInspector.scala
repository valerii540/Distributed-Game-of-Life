package vbosiak.common.utils

import com.typesafe.scalalogging.StrictLogging
import vbosiak.common.models.Capabilities
import vbosiak.common.utils.ConfigProvider._
import vbosiak.master.models.Size

object ResourcesInspector extends StrictLogging {
  private val preservedBytes = ConfigProvider.config.getBytes("simulation.worker.resources.preserved")
  private val sizeOverride   = {
    val h = ConfigProvider.config.getIntOpt("simulation.worker.resources.field-size-override.height")
    val w = ConfigProvider.config.getIntOpt("simulation.worker.resources.field-size-override.width")

    (h, w) match {
      case (Some(height), Some(width)) => Some(Size(height, width))
      case _                           => None
    }
  }

  def inspectNode(): Unit = {
    val mb = 1024 * 1024

    logger.debug("====== Node properties ======")
    logger.debug("==> Max memory: {}MB", Runtime.getRuntime.maxMemory() / mb)
    logger.debug("==> Preserved memory: {}MB", preservedBytes / mb)
    logger.debug("==> Max available memory for field: {}MB", processingCapabilities.availableMemory / mb)
    if (sizeOverride.isDefined)
      logger.warn("==> Field size override enabled: {}x{}", sizeOverride.get.height, sizeOverride.get.width)
    logger.debug("=============================")
  }

  lazy val processingCapabilities: Capabilities = {
    val maxMemory = Runtime.getRuntime.maxMemory()

    if (maxMemory <= preservedBytes)
      throw new OutOfMemoryError("Max memory is lower than preserved")

    val availableMemory =
      if (sizeOverride.isDefined)
        sizeOverride.get.height * sizeOverride.get.width
      else
        (maxMemory - preservedBytes) / 2

    Capabilities(availableMemory)
  }
}
