package vbosiak.master.helpers

import akka.actor.typed.ActorSystem
import akka.stream.alpakka.csv.scaladsl.CsvFormatting
import akka.stream.scaladsl.{FileIO, Source}
import vbosiak.common.models.WorkerIterationResult
import vbosiak.common.utils.ConfigProvider

import java.nio.file.{Paths, StandardOpenOption}
import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

trait LogWriter {
  def writeHeader(workers: Set[String]): Unit
  def writeLog(iteration: Long, results: Set[WorkerIterationResult]): Unit
}

object DummyWriter extends LogWriter {
  override def writeHeader(workers: Set[String]): Unit                              = ()
  override def writeLog(iteration: Long, results: Set[WorkerIterationResult]): Unit = ()
}

final class LogWriterImpl()(implicit system: ActorSystem[Nothing]) extends LogWriter {
  private val destination                   = Paths.get(ConfigProvider.config.getString("simulation.master.log-writer.destination"))
  system.log.warn(destination.toString)
  private implicit val ec: ExecutionContext = system.executionContext //TODO: use separate EC for blocking ops

  override def writeHeader(workers: Set[String]): Unit = {
    val sortedWorkers = workers.toList.sorted

    Source
      .single(List("Time", "Iteration") ++ sortedWorkers ++ List("Total"))
      .via(CsvFormatting.format())
      .runWith(FileIO.toPath(destination))
      .onComplete {
        case Success(_)         => ()
        case Failure(exception) => system.log.warn("Unable to write into log file:", exception)
      }
  }

  override def writeLog(iteration: Long, results: Set[WorkerIterationResult]): Unit = {
    val sortedResults = results.toList.sortBy(_.ref.path.name).map(_.stats.population)
    val total         = sortedResults.sum

    Source
      .single(List(Instant.now().toString, iteration.toString) ++ sortedResults.map(_.toString) ++ List(total.toString))
      .via(CsvFormatting.format())
      .runWith(FileIO.toPath(destination, Set(StandardOpenOption.WRITE, StandardOpenOption.APPEND)))
      .onComplete {
        case Success(_)         => ()
        case Failure(exception) => system.log.warn("Unable to write into log file:", exception)
      }
  }
}
