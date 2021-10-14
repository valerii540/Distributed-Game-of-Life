package vbosiak.actors

import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.typed.Cluster
import vbosiak.common.serialization.CborSerializable

object Worker {
  type WorkerTopic = Topic.Command[Worker.WorkerMessage]

  sealed trait WorkerMessage extends CborSerializable

  final case class NewAssignment() extends WorkerMessage

  def apply(cluster: Cluster, workerTopic: ActorRef[WorkerTopic]): Behavior[WorkerMessage] =
    Behaviors.setup { context =>
      context.log.info("Hello, I'm worker {} at {}", context.self.path, cluster.selfMember.address)

      workerTopic ! Topic.Subscribe(context.self)

      Behaviors.receiveMessage { case NewAssignment() =>
        context.log.info("Received new assignment")
        Behaviors.same
      }
    }
}
