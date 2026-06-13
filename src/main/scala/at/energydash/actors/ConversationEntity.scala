package at.energydash.actors

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import at.energydash.actors.MqttPublisher.EdaNotification
import at.energydash.domain.EbMsMessage
import at.energydash.domain.dao.{Db, SlickConversationRepository}
import at.energydash.domain.enums.EbMsMessageType
import io.circe.generic.auto._

import scala.util.{Failure, Success}

object ConversationEntity {

  final case class InitConversation(message: EbMsMessage, replyTo: ActorRef[EdaCommand]) extends EdaCommand

  final case class InitDone(message: EbMsMessage) extends EdaCommand

  final case class MergeConversation(message: EbMsMessage, replyTo: ActorRef[EdaCommand]) extends EdaCommand

  final case class ConversationMerged(message: EbMsMessage) extends EdaCommand

  final case class MergeNotification(notification: EdaNotification, replyTo: ActorRef[EdaCommand]) extends EdaCommand

  final case class NotificationMerged(notification: EdaNotification) extends EdaCommand

  final case class MergeError(error: String) extends EdaCommand

  sealed trait Command[ReplyMessage] extends CborSerializable

  final case class SetValue(msg: EbMsMessage, replyTo: ActorRef[Done]) extends Command[Done]
  //  final case class GetValue(replyTo: ActorRef[State]) extends Command[State]

  def mergeEbmsMessage(stored: Option[EbMsMessage], current: EbMsMessage): EbMsMessage = {
    current.messageCode match {
      case EbMsMessageType.ENERGY_SYNC_REJECTION | EbMsMessageType.ENERGY_SYNC_RES =>
        current.copy(meter = stored.flatMap(_.meter), ecId = stored.flatMap(_.ecId))
      case EbMsMessageType.EDA_MSG_ABLEHNUNG_CCMS | EbMsMessageType.EDA_MSG_ANTWORT_CCMS =>
        current.copy(consentEnd = stored.flatMap(_.consentEnd), ecId = stored.flatMap(_.ecId))
      case EbMsMessageType.CHANGE_METER_PARTITION_ANSWER | EbMsMessageType.CHANGE_METER_PARTITION_REJECTION =>
        current.copy(meterList = stored.flatMap(_.meterList), ecId = stored.flatMap(_.ecId))
      case EbMsMessageType.ONLINE_REG_ANSWER | EbMsMessageType.ONLINE_REG_ANSWER | EbMsMessageType.ONLINE_REG_REJECTION | EbMsMessageType.ONLINE_REG_APPROVAL | EbMsMessageType.ONLINE_REG_COMPLETION |
           EbMsMessageType.OFFLINE_REG_ANSWER | EbMsMessageType.OFFLINE_REG_ANSWER | EbMsMessageType.OFFLINE_REG_REJECTION | EbMsMessageType.OFFLINE_REG_APPROVAL | EbMsMessageType.OFFLINE_REG_COMPLETION if stored.flatMap(_.ecId).isDefined =>
        current.copy(ecId = stored.flatMap(_.ecId))
      case EbMsMessageType.ZP_LIST_RESPONSE if stored.flatMap(_.ecId).isDefined =>
        current.copy(ecId = stored.flatMap(_.ecId))
      case _ =>
        current
    }
  }

  import at.energydash.domain.JsonImplicit._

  def apply(): Behavior[EdaCommand] = {
    Behaviors.setup[EdaCommand] { context =>
      implicit val system: ActorSystem[_] = context.system

      import system.executionContext

      val conversationRepo = new SlickConversationRepository(Db.getConfig)

      val logger = context.log
      def merge(message: EbMsMessage) = conversationRepo.findById(message.conversationId).map {
        case Some(conversation) =>
          conversation.conversation match {
            case Some(c) => c.as[EbMsMessage] match {
              case Right(m) => mergeEbmsMessage(Some(m), message)
              case Left(_) => message
            }
            case _ => message
          }
        case _ => message
      }

      Behaviors.receiveMessage {
        case InitConversation(message, replyTo) =>
          conversationRepo.create(message) onComplete {
            case Success(_) => replyTo ! InitDone(message)
            case Failure(e) => {
              logger.error(s"${e.getMessage}")
              replyTo ! InitDone(message)
            }
          }
          Behaviors.same
        case MergeConversation(message, replyTo) =>
          merge(message) onComplete {
            case Success(m) => replyTo ! ConversationMerged(m)
            case Failure(e) =>
              context.log.error(e.getMessage)
              replyTo ! MergeError(e.getMessage)
          }
          Behaviors.same
        case MergeNotification(notification, replyTo) =>
          merge(notification.message) onComplete {
            case Success(m) => replyTo ! NotificationMerged(notification.copy(message = m))
            case Failure(e) =>
              logger.error(e.getMessage)
              replyTo ! MergeError(e.getMessage)
          }
          Behaviors.same
      }
    }
  }
}
