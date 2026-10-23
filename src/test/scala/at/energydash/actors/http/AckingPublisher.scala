package at.energydash.actors.http

import at.energydash.actors.MqttPublisher.{MqttCommand, MqttPublish}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

/** Stand-in for MqttPublisher that takes every message into MQTT at once, as a connected client would, and passes it on. */
object AckingPublisher {
  def apply(probe: ActorRef[MqttCommand]): Behavior[MqttCommand] = Behaviors.receiveMessage { m =>
    m match {
      case MqttPublish(_, Some(handover)) => if (handover.claim()) handover.accepted()
      case _ =>
    }
    probe ! m
    Behaviors.same
  }
}
