package vbosiak.common.utils

import com.typesafe.config.{Config, ConfigFactory}

import scala.util.Try

object ConfigProvider {
  val config: Config = ConfigFactory.load()

  implicit class ConfigExtensions(c: Config) {
    def getIntOpt(path: String): Option[Int] = Try(c.getInt(path)).toOption
  }
}
