package vbosiak.master.helpers

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.ActorContext
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import vbosiak.common.models.{Capabilities, Neighbors, WorkerRep}
import vbosiak.master.actors.Master
import vbosiak.master.controllers.models.Size
import vbosiak.worker.actors.Worker.WorkerCommand

import scala.concurrent.ExecutionContext

final class MasterHelperSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {
  private val testKit = ActorTestKit()

  private val masterHelper = new MasterHelper {
    // We don't test master behaviour here
    override implicit val context: ActorContext[Master.MasterCommand] = null
    override implicit val ec: ExecutionContext                        = null
    override implicit val system: ActorSystem[Nothing]                = null
  }

  override def afterAll(): Unit = testKit.shutdownTestKit()

  "MasterHelper" when {
    "choosing single worker" must {
      "choose worker with required minimal capabilities" in {
        val workers = Set(
          WorkerRep(testKit.createTestProbe("").ref, Capabilities(100)),
          WorkerRep(testKit.createTestProbe("").ref, Capabilities(10)),
          WorkerRep(testKit.createTestProbe("").ref, Capabilities(2000))
        )

        masterHelper.findStandAloneCandidate(Size(10), workers) mustBe workers.find(_.capabilities.availableMemory == 100)
      }
    }

    "choosing more than one worker" must {
      val probes = IndexedSeq[TestProbe[WorkerCommand]](
        testKit.createTestProbe("worker-0"),
        testKit.createTestProbe("worker-1"),
        testKit.createTestProbe("worker-2")
      )

      val workers = Set(
        WorkerRep(probes(0).ref, Capabilities(100)),
        WorkerRep(probes(1).ref, Capabilities(100)),
        WorkerRep(probes(2).ref, Capabilities(100))
      )

      "greedy divide universe between workers" in {
        val size  = Size(10, 25)
        val parts = masterHelper.divideUniverseBetweenWorkers(size, workers).toSeq.map(_._2)

        parts mustBe Seq(Size(10), Size(10), Size(10, 5))
      }

      "assign neighbors for workers" in {
        val size = Size(10, 25)

        val workersWithNeighbors = masterHelper
          .divideUniverseBetweenWorkers(size, workers)
          .map(t => t._1.actor -> t._1.neighbors)
        val expected             = workers.map { w =>
          val neighbors =
            w.actor.path.name match {
              case s"worker-0-$_" => Some(Neighbors(probes(2).ref, probes(1).ref))
              case s"worker-1-$_" => Some(Neighbors(probes(0).ref, probes(2).ref))
              case s"worker-2-$_" => Some(Neighbors(probes(1).ref, probes(0).ref))
            }
          w.actor -> neighbors
        }

        workersWithNeighbors mustBe expected
      }
    }
  }

}
