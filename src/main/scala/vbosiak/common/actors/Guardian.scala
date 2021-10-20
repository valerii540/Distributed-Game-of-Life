package vbosiak.common.actors

import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop}
import akka.cluster.typed.Cluster
import akka.http.scaladsl.Http
import akka.management.scaladsl.AkkaManagement
import vbosiak.common.utils.ResourcesInspector
import vbosiak.master.actors.{Coordinator, Master}
import vbosiak.master.controllers.MasterController
import vbosiak.worker.actors.Worker
import vbosiak.worker.actors.Worker.WorkerTopic

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContextExecutor}

object Guardian {
  def apply(): Behavior[Unit] =
    Behaviors.setup { context =>
      val cluster                            = Cluster(context.system)
      val workerTopic: ActorRef[WorkerTopic] =
        context.spawn(Topic[Worker.WorkerCommand]("worker-topic"), "worker-topic-actor")

      if (cluster.selfMember.hasRole("master")) {
        val managementRoutes = AkkaManagement(context.system).routes

        val masterRef = context.spawn(Master(cluster, workerTopic), "master")
        context.spawn(Coordinator(cluster, masterRef), "coordinator")

        implicit val system: ActorSystem[Nothing] = context.system
        val masterController                      = new MasterController(masterRef)
        val binding                               = Http().newServerAt("localhost", 8080).bind(masterController.routes(managementRoutes))

        Behaviors.receiveSignal { case (context, PostStop) =>
          implicit val ec: ExecutionContextExecutor = context.system.executionContext
          Await.result(binding.flatMap(_.unbind()), 5.seconds)
          Behaviors.same
        }
      } else {
        ResourcesInspector.inspectNode()

        context.spawn(Worker(cluster, workerTopic), "worker")
        Behaviors.empty
      }
    }
}
