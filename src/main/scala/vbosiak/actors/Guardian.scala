package vbosiak.actors

import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.typed.Cluster
import akka.management.scaladsl.AkkaManagement
import com.typesafe.config.Config
import vbosiak.actors.Worker.WorkerTopic
import vbosiak.common.utils.ResourcesInspector

object Guardian {
  def apply(config: Config): Behavior[Unit] =
    Behaviors.setup { context =>
      val cluster                            = Cluster(context.system)
      val workerTopic: ActorRef[WorkerTopic] =
        context.spawn(Topic[Worker.WorkerMessage]("worker-topic"), "worker-topic-actor")

      if (cluster.selfMember.hasRole("master")) {
        AkkaManagement(context.system).start()

        val desiredWorkersCount = config.getInt("akka.cluster.required-num.workers")
        val masterRef           = context.spawn(Master(cluster, workerTopic), "master")
        context.spawn(Coordinator(cluster, masterRef, desiredWorkersCount), "coordinator")
      } else {
        ResourcesInspector.inspectNode()

        context.spawn(Worker(cluster, workerTopic), "worker")
      }

      Behaviors.empty[Unit]
    }
}
