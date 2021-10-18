package vbosiak.worker.actors

import akka.Done
import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.typed.Cluster
import vbosiak.common.models.{CborSerializable, Neighbors}
import vbosiak.common.utils.ResourcesInspector
import vbosiak.master.actors.Master.{MasterCommand, WorkerCapabilities}

object Worker {
  type WorkerTopic = Topic.Command[Worker.WorkerCommand]

  sealed trait WorkerCommand extends CborSerializable

  final case class TellCapabilities(replyTo: ActorRef[MasterCommand])                           extends WorkerCommand
  final case class NewSimulation(replyTo: ActorRef[Done], fieldSize: Int, neighbors: Neighbors) extends WorkerCommand

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
      Behaviors.receiveMessagePartial { case NewSimulation(replyTo, fieldSize, neighbors) =>
        val filed = Array.ofDim[Boolean](fieldSize, fieldSize)

        context.log.info("Created {}x{} empty field", filed.length, filed.head.length)

        replyTo ! Done
        gameLifeCycle(master, neighbors, filed)
      }
    }

  def gameLifeCycle(
      master: ActorRef[MasterCommand],
      neighbors: Neighbors,
      field: Array[Array[Boolean]]
  ): Behavior[WorkerCommand] =
    Behaviors.setup(context => Behaviors.empty)
}
