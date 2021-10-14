package vbosiak.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.ClusterEvent.{MemberEvent, MemberExited, MemberUp}
import akka.cluster.typed.{Cluster, Subscribe}

object Coordinator {
  def apply(cluster: Cluster, masterRef: ActorRef[Master.MasterMessage], desiredWorkersCount: Int): Behavior[MemberEvent] =
    Behaviors.setup { context =>
      context.log.info("Hello, I'm coordinator {} at {}", context.self.path, cluster.selfMember.address)

      cluster.subscriptions ! Subscribe(context.self, classOf[MemberEvent])

      coordinatorLifeCycle(0, desiredWorkersCount, masterRef)
    }

  private def coordinatorLifeCycle(
      workerCounter: Int,
      desiredWorkersCount: Int,
      masterRef: ActorRef[Master.MasterMessage]
  ): Behavior[MemberEvent] =
    Behaviors.setup { context =>
      if (workerCounter == desiredWorkersCount) {
        context.log.info("Game Of Life cluster is ready to start simulation")
        masterRef ! Master.ClusterIsReady
      } else
        context.log.info("{}/{} workers are ready. Waiting...", workerCounter, desiredWorkersCount)

      Behaviors.receiveMessage {
        case MemberUp(member) if member.hasRole("worker")     =>
          coordinatorLifeCycle(workerCounter + 1, desiredWorkersCount, masterRef)
        case MemberExited(member) if member.hasRole("worker") =>
          context.log.warn("{} have left the cluster", member)
          coordinatorLifeCycle(workerCounter - 1, desiredWorkersCount, masterRef)
        case _                                                =>
          Behaviors.same
      }
    }
}
