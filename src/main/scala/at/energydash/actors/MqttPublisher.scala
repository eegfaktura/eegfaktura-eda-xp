package at.energydash.actors

import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.util.Timeout
import at.energydash.domain.EbMsMessage
import at.energydash.mqtt.CommandMessage
import at.energydash.mqtt.MqttProtocol.{EdaEventReceived, EdaInboundMessage, EdaMessageCommand, MqttCmd}
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration.DurationInt

object MqttPublisher extends StrictLogging{

  trait MqttCommand
  case class EdaNotification(protocol: String, message: EbMsMessage)
  case class MqttPublish(mails: List[EdaNotification]/*, mailProvider: ActorRef[EmailCommand]*/) extends MqttCommand
  case class MqttPublishCommand(command: CommandMessage) extends MqttCommand
  case class MqttPublishError(tenant: String, message: String) extends MqttCommand

  case class AggregateNotification(tenant: String, mails: List[EdaNotification], replyTo: ActorRef[MqttCommand]) extends MqttCommand

  implicit val timeout: Timeout = Timeout(5.seconds)


  def apply(
             mqttSystem: ActorRef[MqttCmd],
             ebMsAggregator: ActorRef[MqttCommand]): Behavior[MqttCommand] = {

    Behaviors.setup { implicit ctx =>
      import ctx.{executionContext, system}

      def process(): Behavior[MqttCommand] =
        Behaviors.receiveMessage {
          case MqttPublish(notification) =>
            ebMsAggregator.ask(ref => AggregateNotification("", notification, ref)) map {
              case MqttPublish(n) =>
                n.foreach(x => mqttSystem ! EdaEventReceived(EdaInboundMessage(x.protocol, x.message), None))
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
