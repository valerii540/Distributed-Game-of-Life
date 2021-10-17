package vbosiak.common.utils

import com.typesafe.scalalogging.StrictLogging

object ResourcesInspector extends StrictLogging {
  final case class Capabilities(availableMemory: Long, maxFiledSideSize: Int)

  private val preservedBytes = ConfigProvider.config.getBytes("resources.preserved")

  def inspectNode(): Unit = {
    val runtime = Runtime.getRuntime
    val mb      = 1024 * 1024

    logger.debug("====== Node properties ======")
    logger.debug("==> Max memory: {}MB", runtime.maxMemory() / mb)
    logger.debug("==> Preserved memory: {}MB", preservedBytes / mb)
    logger.debug("==> Max available memory for processing: {}MB", processingCapabilities.availableMemory / mb)
    logger.debug(
      "==> Max field size: {}x{} cells",
      processingCapabilities.maxFiledSideSize,
      processingCapabilities.maxFiledSideSize
    )
    logger.debug("=============================")
  }

  lazy val processingCapabilities: Capabilities = {
    val maxMemory = Runtime.getRuntime.maxMemory()

    if (maxMemory <= preservedBytes)
      throw new OutOfMemoryError("Max memory is lower than preserved")

    val availableMemory = maxMemory - preservedBytes

    Capabilities(
      availableMemory = availableMemory,
      maxFiledSideSize = Math.floor(Math.sqrt(availableMemory.toDouble)).toInt
    )
  }
}
