package at.energydash.actors.http

import at.energydash.actors.MqttPublisher.{MqttCommand, MqttPublish}
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

/** Stand-in for MqttPublisher that confirms every publish at once, as the broker would, and passes it on. */
object AckingPublisher {
  def apply(probe: ActorRef[MqttCommand]): Behavior[MqttCommand] = Behaviors.receiveMessage { m =>
    m match {
      case MqttPublish(_, Some(delivered)) => delivered.trySuccess(Done)
      case _ =>
    }
    probe ! m
    Behaviors.same
  }
}
