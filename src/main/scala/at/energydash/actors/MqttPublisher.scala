package at.energydash.actors

import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.util.Timeout
import at.energydash.domain.EbMsMessage
import at.energydash.mqtt.CommandMessage
import at.energydash.mqtt.MqttProtocol.{EdaEventReceived, EdaInboundMessage, EdaMessageCommand, Handover, MqttCmd}
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object MqttPublisher extends StrictLogging{

  trait MqttCommand
  case class EdaNotification(protocol: String, message: EbMsMessage)
  /**
   * `handover`, when given (one inbound message), completes once the notification is in the MQTT
   * publishing stream and fails when it will not be published: the aggregator fails or replaces it
   * with an error, or MQTT does not take it (261005-ca1). Without it nothing is reported, as before.
   */
  case class MqttPublish(mails: List[EdaNotification]/*, mailProvider: ActorRef[EmailCommand]*/, handover: Option[Handover] = None) extends MqttCommand
  case class MqttPublishCommand(command: CommandMessage) extends MqttCommand
  case class MqttPublishError(tenant: String, message: String) extends MqttCommand

  case class AggregateNotification(tenant: String, mails: List[EdaNotification], replyTo: ActorRef[MqttCommand]) extends MqttCommand

  implicit val timeout: Timeout = Timeout(5.seconds)


  def apply(
             mqttSystem: ActorRef[MqttCmd],
             ebMsAggregator: ActorRef[MqttCommand]): Behavior[MqttCommand] = {

    Behaviors.setup { implicit ctx =>
      import ctx.{executionContext, system}

      /** Refuses the hand-over, whose owner logs it; without one the failure is logged here (it used to vanish). */
      def notPublished(notification: List[EdaNotification], handover: Option[Handover], reason: String): Unit =
        handover match {
          case Some(h) => h.refused(new IllegalStateException(reason))
          case None => logger.error(s"Notification (conversation ${notification.map(_.message.conversationId).mkString(", ")}) not published: $reason")
        }

      def publish(n: List[EdaNotification]): Unit =
        n.foreach(x => mqttSystem ! EdaEventReceived(EdaInboundMessage(x.protocol, x.message), None))

      def process(): Behavior[MqttCommand] =
        Behaviors.receiveMessage {
          case MqttPublish(notification, handover) =>
            ebMsAggregator.ask(ref => AggregateNotification("", notification, ref)) onComplete {
              case Success(MqttPublish(n, _)) if handover.isDefined && n.exists(_.protocol == EbMsAggregator.ReplacedProtocol) =>
                // published to the error topic as before, but the original message is not
                publish(n)
                notPublished(notification, handover, s"aggregator replaced the message with an error: ${n.flatMap(_.message.errorMessage).mkString("; ")}")
              case Success(MqttPublish(single :: Nil, _)) if handover.isDefined =>
                mqttSystem ! EdaEventReceived(EdaInboundMessage(single.protocol, single.message), None, handover)
              case Success(MqttPublish(n, _)) if handover.isDefined =>
                notPublished(notification, handover, s"expected one notification from the aggregator, got ${n.size}")
              case Success(MqttPublish(n, _)) =>
                publish(n)
              case Success(MqttPublishError(_, msg)) =>
                notPublished(notification, handover, s"aggregator failed: $msg")
              case Success(other) =>
                notPublished(notification, handover, s"unexpected aggregator answer ${other.getClass.getSimpleName}")
              case Failure(e) =>
                notPublished(notification, handover, s"aggregator did not answer: ${e.getMessage}")
            }
            Behaviors.same
          case MqttPublishCommand(command) =>
            mqttSystem ! EdaMessageCommand(command, None)
            Behaviors.same
          case MqttPublishError(tenantId, msg) =>
            ctx.log.info(s"Error ${tenantId} ${msg}")
            Behaviors.same
        }

      process()
    }
  }
}
