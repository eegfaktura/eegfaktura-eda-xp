package at.energydash.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.typed.scaladsl.ActorFlow
import akka.util.Timeout
import at.energydash.actors.ConversationEntity.{MergeError, MergeNotification, NotificationMerged}
import at.energydash.actors.MqttPublisher.{AggregateNotification, EdaNotification, MqttCommand, MqttPublish, MqttPublishError}
import at.energydash.domain.DefaultEbMsMessage
import at.energydash.domain.dao.{Db, EegMaster, SlickEegMasterRepository}
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.domain.enums.EbMsMessageType.EbMsMessageType

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


object EbMsAggregator {

  private case class AggregatorException(reason: String, cause: Option[Throwable] = None) extends RuntimeException(reason) {
    cause.foreach(initCause)
  }

  private def checkEcIdIsNecessary(t: EbMsMessageType) = t match {
    case EbMsMessageType.CHANGE_METER_PARTITION_ANSWER | EbMsMessageType.CHANGE_METER_PARTITION_REJECTION => true
    case EbMsMessageType.ENERGY_FILE_RESPONSE => true
    case EbMsMessageType.ONLINE_REG_ANSWER | EbMsMessageType.ONLINE_REG_APPROVAL | EbMsMessageType.ONLINE_REG_COMPLETION | EbMsMessageType.ONLINE_REG_REJECTION => true
    case EbMsMessageType.OFFLINE_REG_ANSWER | EbMsMessageType.OFFLINE_REG_APPROVAL | EbMsMessageType.OFFLINE_REG_COMPLETION | EbMsMessageType.OFFLINE_REG_REJECTION => true
    case EbMsMessageType.ENERGY_SYNC_RES | EbMsMessageType.ENERGY_SYNC_REJECTION => true
    case _ => false

  }

  def apply(conversationEntity: ActorRef[EdaCommand]): Behavior[MqttCommand] = {
    Behaviors.setup { implicit ctx =>
      implicit val ex: ExecutionContext = ctx.system.executionContext
      implicit val mat: Materializer = Materializer(ctx)
      implicit val timeout: Timeout = Timeout(5.seconds)

      val eegRepo = new SlickEegMasterRepository(Db.getConfig)

      Behaviors.receiveMessage {
        case AggregateNotification(tenant, notifications, replyTo) =>
          Source(notifications)
            .via {
              ActorFlow.ask(parallelism = 1)(conversationEntity)((msg: EdaNotification, replyTo: ActorRef[EdaCommand]) =>
                MergeNotification(msg, replyTo)).collect {
                case NotificationMerged(m) => m
                case MergeError(error) => EdaNotification("error", DefaultEbMsMessage.Error(error))
              }
            }
            .mapAsync(1) {
              case m if checkEcIdIsNecessary(m.message.messageCode) =>
                if (m.message.ecId.isEmpty) {
                  eegRepo.byTenant(m.message.receiver).map {
                    case Some(EegMaster(_, communityId)) =>
                      MqttPublisher.EdaNotification(m.protocol,
                        m.message.copy(ecId = Some(communityId)))
                    case _ => m
                  }
                } else {
                  Future(m)
                }
              case m => Future(m)
            }
            .recover {
              case e =>
                ctx.log.error(s"Recover Aggregator: ${e.getMessage}")
                EdaNotification("error", DefaultEbMsMessage.Error(e.getMessage))
            }
            .runWith(Sink.collection[MqttPublisher.EdaNotification, List[MqttPublisher.EdaNotification]])
            .onComplete {
              case Success(value) => replyTo ! MqttPublish(value)
              case Failure(e) => replyTo ! MqttPublishError(tenant, s"$e - ${e.getMessage}")
            }
          Behaviors.same
      }
    }
  }
}
