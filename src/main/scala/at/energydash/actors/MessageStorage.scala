package at.energydash.actors

import scala.language.postfixOps

//object MessageStorage {
//  sealed trait Command[Reply <: CommandReply] {
//    def replyTo: ActorRef[Reply]
//  }
//
//  sealed trait Event
//  sealed trait CommandReply
//
//  sealed trait AddMessageResult extends CommandReply
//  case class Added(msg: EbMsMessage) extends AddMessageResult
//  final case class MessageAdded(id: String, messageId: String, message: EbMsMessage)
//  extends Event
//    with CborSerializable
//
//  final case class AddMessage(message: EbMsMessage, replyTo: ActorRef[AddMessageResult])
//  extends Command[AddMessageResult]
//
//  sealed trait FindMessageResult extends CommandReply
//  case class MessageFound(conversation: StoredConversation) extends FindMessageResult
//  case class MessageNotFound(id: String) extends FindMessageResult
//  final case class FindById(id: String, replyTo: ActorRef[FindMessageResult])
//  extends Command[FindMessageResult]
//  final case class FindAll(replyTo: ActorRef[FindMessageResult]) extends Command[FindMessageResult]
//  case class MessageAllFound(conversations: List[StoredConversation]) extends FindMessageResult
//
//  case class MergeMessage(m: EbMsMessage, replyTo: ActorRef[FindMessageResult]) extends Command[FindMessageResult]
//  case class MergedMessage(m: EbMsMessage) extends FindMessageResult
//
//  sealed trait UpdateMessageResult extends CommandReply
//  case class Updated(msg: EbMsMessage) extends UpdateMessageResult
//  final case class UpdateMessage(message: EbMsMessage, replyTo: ActorRef[UpdateMessageResult])
//    extends Command[UpdateMessageResult]
//  final case class UpdateEcId(id: String, ecId: String, replyTo: ActorRef[UpdateMessageResult])
//    extends Command[UpdateMessageResult]
//  final case class MessageUpdated(id: String, message: EbMsMessage)
//    extends Event
//      with CborSerializable
//  final case class EcIdUpdated(id: String, ecId: String)
//    extends Event
//      with CborSerializable
//
//  // state definition
//  final case class Storage(conversations: Map[String, StoredConversation] = Map.empty) {
//    def applyEvent(event: Event): Storage = event match {
//      case MessageAdded(id, messageId, message) =>
//        copy(conversations = conversations.updated(id, conversations.get(id) match {
//          case Some(_) => StoredConversation(id, Some(message))
//          case None => StoredConversation(id, Some(message))
//        }))
//      case MessageUpdated(id, message) =>
//        conversations.get(id) match {
//          case Some(_) => copy(conversations = conversations.updated(id, StoredConversation(id, Some(message))))
//          case None => copy(conversations = conversations.updated(id, StoredConversation(id, Some(message))))
//        }
//      case EcIdUpdated(id, ecId) =>
//        conversations.get(id) match {
//          case Some(m) => copy(conversations = conversations.updated(id,
//            StoredConversation(id, m.message.map(m => m.copy(ecId=Some(ecId))))))
//          case None => this
//        }
//    }
//
//    def applyCommand(context: ActorContext[Command[_]], cmd: Command[_]): ReplyEffect[Event, Storage] = cmd match {
//      case AddMessage(message, replyTo) =>
//        val event = MessageAdded(message.conversationId, message.messageId.get, message)
//        Effect.persist(event).thenReply(replyTo)(_ => Added(event.message))
//      case UpdateMessage(message, replyTo) =>
//        val event = MessageUpdated(message.conversationId, message)
//        Effect.persist(event).thenReply(replyTo)(_ => Updated(event.message))
//      case UpdateEcId(id, ecId, replyTo) =>
//        val event = EcIdUpdated(id, ecId)
//        Effect.persist(event).thenReply(replyTo)(s => Updated(s.conversations(id).message.get))
//      case FindById(id, replyTo) if conversations.contains(id) =>
//        Effect.reply(replyTo)(MessageFound(conversations(id)))
//      case FindById(id, replyTo) if !conversations.contains(id) =>
//        Effect.reply(replyTo)(MessageNotFound(id))
//      case FindAll(replyTo) =>
//        Effect.reply(replyTo)(MessageAllFound(conversations.map{ case (_, value) => value } toList))
//      case MergeMessage(m, replyTo) =>
//        Effect.reply(replyTo)(MergedMessage(mergeEbmsMessage(conversations.get(m.conversationId).flatMap(_.message), m)))
//    }
//  }
//
//  case class StoredConversation(conversationId: String, message: Option[EbMsMessage])
//
//  def apply(): Behavior[Command[_]] = Behaviors.setup { context =>
//    EventSourcedBehavior.withEnforcedReplies[Command[_], Event, Storage](
//      persistenceId = PersistenceId.ofUniqueId("conversationstorage"),
//      emptyState = Storage(),
//      commandHandler = (state, cmd) => state.applyCommand(context, cmd),
//      eventHandler = (state, evt) => state.applyEvent(evt))
////      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 10, keepNSnapshots = 3))
////      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
//  }
//}
