package vbosiak.worker.actors

import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.typed.Cluster
import vbosiak.common.models.CborSerializable
import vbosiak.common.utils.ResourcesInspector
import vbosiak.master.actors.Master.{MasterCommand, WorkerCapabilities}
import vbosiak.models.WorkerRep.Neighbors

object Worker {
  type WorkerTopic = Topic.Command[Worker.WorkerCommand]

  sealed trait WorkerCommand extends CborSerializable

  final case class TellCapabilities(replyTo: ActorRef[MasterCommand])   extends WorkerCommand
  final case class NewAssignment(fieldSize: Long, neighbors: Neighbors) extends WorkerCommand

  def apply(cluster: Cluster, workerTopic: ActorRef[WorkerTopic]): Behavior[WorkerCommand] =
    Behaviors.setup { context =>
      context.log.info("Hello, I'm worker {} at {}", context.self.path, cluster.selfMember.address)

      workerTopic ! Topic.Subscribe(context.self)

      initialLifeCycle()
    }

  def initialLifeCycle(): Behavior[WorkerCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessagePartial { case TellCapabilities(master) =>
        master ! WorkerCapabilities(ResourcesInspector.processingCapabilities, context.self)
        initialLifeCycle(master)
      }
    }

  def initialLifeCycle(master: ActorRef[MasterCommand]): Behavior[WorkerCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessagePartial {
        case NewAssignment(fieldSize, neighbors) =>
          gameLifeCycle(master, neighbors)
      }
    }

  def gameLifeCycle(master: ActorRef[MasterCommand], neighbors: Neighbors): Behavior[WorkerCommand] =
    Behaviors.setup { context =>

      Behaviors.receiveMessage {
        ???
      }
    }
}
