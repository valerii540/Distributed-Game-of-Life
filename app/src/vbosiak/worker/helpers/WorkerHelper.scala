package vbosiak.worker.helpers

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.cluster.typed.{Cluster, Leave}
import vbosiak.common.utils.FieldFormatter._
import vbosiak.worker.actors.Worker._

private[worker] trait WorkerHelper {
  implicit val context: ActorContext[WorkerCommand]

  def handleDieCommand(reason: String): Behavior[WorkerCommand] = {
    val cluster = Cluster(context.system)
    context.log.error("Received poison pill from master because of \"{}\"", reason)
    cluster.manager ! Leave(cluster.selfMember.address)
    Behaviors.same
  }

  def handleWrongCommand(command: WorkerCommand, currentBehaviourName: String): Behavior[WorkerCommand] = {
    context.log.warn("Received {} in {} behaviour: {}", command, currentBehaviourName)
    Behaviors.same
  }

  def handleShowCommand(field: Field): Behavior[WorkerCommand] = {
    context.log.info("Received show command:\n{}", field.beautify)
    Behaviors.same
  }

  def handleNewSimulationCommand(command: NewSimulation, initialBehaviour: Behavior[WorkerCommand]): Behavior[WorkerCommand] = {
    context.log.info("Resetting self to initial state and start preparing new simulation")
    context.self ! command
    initialBehaviour
  }
}
