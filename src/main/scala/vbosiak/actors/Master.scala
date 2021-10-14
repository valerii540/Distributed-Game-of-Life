package vbosiak.actors

import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.typed._
import vbosiak.actors.Worker.WorkerTopic
import vbosiak.common.serialization.CborSerializable

object Master {
  sealed trait MasterMessage extends CborSerializable

  case object ClusterIsReady extends MasterMessage

  def apply(cluster: Cluster, workerTopic: ActorRef[WorkerTopic]): Behavior[MasterMessage] =
    Behaviors.setup { context =>
      context.log.info("Hello, I'm master {} at {}", context.self.path, cluster.selfMember.address)

      masterLifeCycle(workerTopic)
    }

  private def masterLifeCycle(workerTopic: ActorRef[WorkerTopic]): Behavior[MasterMessage] =
    Behaviors.setup[MasterMessage] { context =>
      Behaviors.receiveMessage[MasterMessage] { case ClusterIsReady =>
        context.log.info("Received readiness message from coordinator")
        workerTopic ! Topic.Publish(Worker.NewAssignment())
        Behaviors.same
      }
    }
}
