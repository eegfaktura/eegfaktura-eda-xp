package at.energydash.actors

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.util.Timeout
import at.energydash.domain.EbMsMessage
import at.energydash.mqtt.CommandMessage
import at.energydash.mqtt.MqttProtocol.{EdaEventReceived, EdaInboundMessage, EdaMessageCommand, MqttCmd}
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

object MqttPublisher extends StrictLogging{

  trait MqttCommand
  case class EdaNotification(protocol: String, message: EbMsMessage)
  /**
   * `delivered`, when given, completes once the broker confirmed every notification (QoS 1 PUBACK)
   * and fails when one of them is not published: the aggregator fails or replaces it with an error,
   * or MQTT is not connected (261005-ca1). Without it nothing is confirmed, as before.
   */
  case class MqttPublish(mails: List[EdaNotification]/*, mailProvider: ActorRef[EmailCommand]*/, delivered: Option[Promise[Done]] = None) extends MqttCommand
  case class MqttPublishCommand(command: CommandMessage) extends MqttCommand
  case class MqttPublishError(tenant: String, message: String) extends MqttCommand

  case class AggregateNotification(tenant: String, mails: List[EdaNotification], replyTo: ActorRef[MqttCommand]) extends MqttCommand

  implicit val timeout: Timeout = Timeout(5.seconds)


  def apply(
             mqttSystem: ActorRef[MqttCmd],
             ebMsAggregator: ActorRef[MqttCommand]): Behavior[MqttCommand] = {

    Behaviors.setup { implicit ctx =>
      import ctx.{executionContext, system}

      /** Fails `delivered`, whose owner logs it; without one the failure is logged here (it used to vanish). */
      def notDelivered(notification: List[EdaNotification], delivered: Option[Promise[Done]], reason: String): Unit =
        delivered match {
          case Some(d) => d.tryFailure(new IllegalStateException(reason))
          case None => logger.error(s"Notification (conversation ${notification.map(_.message.conversationId).mkString(", ")}) not published: $reason")
        }

      def publish(n: List[EdaNotification], delivered: Option[Promise[Done]]): Unit =
        delivered match {
          case None =>
            n.foreach(x => mqttSystem ! EdaEventReceived(EdaInboundMessage(x.protocol, x.message), None))
          case Some(d) =>
            val confirmed = n.map { x =>
              val p = Promise[Done]()
              mqttSystem ! EdaEventReceived(EdaInboundMessage(x.protocol, x.message),
                Some(() => { p.trySuccess(Done); Future.successful(Done) }),
                Some(e => p.tryFailure(e)))
              p.future
            }
            d.tryCompleteWith(Future.sequence(confirmed).map(_ => Done))
        }

      def process(): Behavior[MqttCommand] =
        Behaviors.receiveMessage {
          case MqttPublish(notification, delivered) =>
            ebMsAggregator.ask(ref => AggregateNotification("", notification, ref)) onComplete {
              case Success(MqttPublish(n, _)) if delivered.isDefined && n.exists(_.protocol == EbMsAggregator.ReplacedProtocol) =>
                // published to the error topic as before, but the original message is not delivered
                publish(n, None)
                notDelivered(notification, delivered, s"aggregator replaced the message with an error: ${n.flatMap(_.message.errorMessage).mkString("; ")}")
              case Success(MqttPublish(n, _)) =>
                publish(n, delivered)
              case Success(MqttPublishError(_, msg)) =>
                notDelivered(notification, delivered, s"aggregator failed: $msg")
              case Success(other) =>
                notDelivered(notification, delivered, s"unexpected aggregator answer ${other.getClass.getSimpleName}")
              case Failure(e) =>
                notDelivered(notification, delivered, s"aggregator did not answer: ${e.getMessage}")
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
