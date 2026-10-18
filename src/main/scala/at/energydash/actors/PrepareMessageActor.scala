package at.energydash.actors

import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.persistence.typed.{PersistenceId, RecoveryCompleted}
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import at.energydash.domain.EbMsMessage
import at.energydash.domain.eda.MessageHelper
import com.typesafe.config.{Config => TsConfig}

import scala.concurrent.duration.DurationInt

object PrepareMessageActor {
  sealed trait Command[Reply <: CommandReply] {
    def replyTo: ActorRef[Reply]
  }
  sealed trait Event
  sealed trait CommandReply

  final case class IdInkremented(messageId: Long)
    extends Event with CborSerializable


  sealed trait PrepareMessageResult extends CommandReply
  case class Prepared(message: EbMsMessage) extends PrepareMessageResult
  final case class PrepareMessage(message: EbMsMessage, replyTo: ActorRef[PrepareMessageResult])
    extends Command[PrepareMessageResult]

  // the counter is formatted with 10 digits (MessageHelper.formatSeqNumber); a floor above this
  // leaves room for 999,999,999 messages before the ids get longer
  val MaxSeqFloor: Long = 9000000000L

  /**
   * `app.message-seq-floor` (reference.conf, env MESSAGE_SEQ_FLOOR): the counter continues from at
   * least this value. The Ponton header id `MSG<counter>@<RC>` has no time stamp, so an adapter of a
   * second zone (pilot) that starts at 0 would repeat ids that prod already used for a community
   * that moved there. 0 (default) = unchanged.
   */
  def seqFloor(config: TsConfig): Long = {
    val floor = config.getLong("app.message-seq-floor")
    if (floor < 0 || floor > MaxSeqFloor)
      throw new IllegalArgumentException(s"app.message-seq-floor must be between 0 and $MaxSeqFloor: $floor")
    floor
  }

  // state definition
  final case class Storage(messageId: Long = 0) extends CborSerializable {
    def applyEvent(event: Event): Storage = event match {
      case IdInkremented(messageId) =>
        copy(messageId = messageId)
    }

    def applyCommand(context: ActorContext[Command[_]], cmd: Command[_], seqFloor: Long): ReplyEffect[Event, Storage] = cmd match {
      case PrepareMessage(message, replyTo) =>
        val current = math.max(messageId, seqFloor)
        val event = IdInkremented(current+1)
        val msgId = MessageHelper.buildMessageId(message.sender, event.messageId)
        val conversationId = MessageHelper.buildMessageId(message.sender, event.messageId+1)
        Effect.persist(event).thenReply(replyTo)(_ =>
          Prepared(message.copy(messageId = Some(msgId), conversationId=conversationId, requestId=Some(MessageHelper.buildRequestId(msgId)), seqNr=Some(current))))
    }
  }

  def apply(seqFloor: Long = 0): Behavior[Command[_]] = Behaviors.setup { context =>
    EventSourcedBehavior.withEnforcedReplies[Command[_], Event, Storage](
      persistenceId = PersistenceId.ofUniqueId("preparemessage"),
      emptyState = Storage(),
      commandHandler = (state, cmd) => state.applyCommand(context, cmd, seqFloor),
      eventHandler = (state, evt) => state.applyEvent(evt))
      .receiveSignal { case (state, RecoveryCompleted) =>
        // operators check this line after setting a floor (runbook EEG move, stage 2)
        context.log.info("Message counter at {}, floor {} (app.message-seq-floor), next Ponton header id MSG{}@<RC>",
          state.messageId, seqFloor, MessageHelper.formatSeqNumber(math.max(state.messageId, seqFloor)))
      }
      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 10, keepNSnapshots = 1).withDeleteEventsOnSnapshot)
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 2.seconds, 0.1))
  }
}
