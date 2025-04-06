package at.energydash.actors

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import at.energydash.domain.EbMsMessage
import at.energydash.domain.eda.MessageHelper

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

  // state definition
  final case class Storage(messageId: Long = 0) {
    def applyEvent(event: Event): Storage = event match {
      case IdInkremented(messageId) =>
        copy(messageId = messageId)
    }

    def applyCommand(context: ActorContext[Command[_]], cmd: Command[_]): ReplyEffect[Event, Storage] = cmd match {
      case PrepareMessage(message, replyTo) =>
        val event = IdInkremented(messageId+1)
        val msgId = MessageHelper.buildMessageId(message.sender, event.messageId)
        val conversationId = MessageHelper.buildMessageId(message.sender, event.messageId+1)
        Effect.persist(event).thenReply(replyTo)(_ =>
          Prepared(message.copy(messageId = Some(msgId), conversationId=conversationId, requestId=Some(MessageHelper.buildRequestId(msgId)), seqNr=Some(messageId))))
    }
  }

  def apply(): Behavior[Command[_]] = Behaviors.setup { context =>
    EventSourcedBehavior.withEnforcedReplies[Command[_], Event, Storage](
      persistenceId = PersistenceId.ofUniqueId("preparemessage"),
      emptyState = Storage(),
      commandHandler = (state, cmd) => state.applyCommand(context, cmd),
      eventHandler = (state, evt) => state.applyEvent(evt))
      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 10, keepNSnapshots = 1).withDeleteEventsOnSnapshot)
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 2.seconds, 0.1))
  }
}
