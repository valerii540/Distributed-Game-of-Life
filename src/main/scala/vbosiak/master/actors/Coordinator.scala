package vbosiak.master.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.ClusterEvent.{MemberEvent, MemberExited, MemberUp}
import akka.cluster.typed.{Cluster, Subscribe}
import vbosiak.master.actors.Master.ClusterNotReady

object Coordinator {
  def apply(cluster: Cluster, masterRef: ActorRef[Master.MasterCommand]): Behavior[MemberEvent] =
    Behaviors.setup { context =>
      context.log.info("Hello, I'm coordinator {} at {}", context.self.path, cluster.selfMember.address)

      cluster.subscriptions ! Subscribe(context.self, classOf[MemberEvent])

      coordinatorLifeCycle(0, masterRef)
    }

  private def coordinatorLifeCycle(
      workerCounter: Int,
      masterRef: ActorRef[Master.MasterCommand]
  ): Behavior[MemberEvent] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case MemberUp(member) if member.hasRole("worker") =>
          coordinatorLifeCycle(workerCounter + 1, masterRef)

        case MemberExited(member) if member.hasRole("worker") =>
          context.log.warn("{} have left the cluster", member)
          masterRef ! ClusterNotReady
          coordinatorLifeCycle(workerCounter - 1, masterRef)

        case _ => Behaviors.same
      }
    }
}
