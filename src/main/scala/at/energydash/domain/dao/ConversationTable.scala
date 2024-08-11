package at.energydash.domain.dao


import io.circe.Json
import slick.jdbc.PostgresProfile.api._
import slick.lifted.ProvenShape

trait ConversationTable {

  import PostgresProfiler.api._

  class ConversationId(tag: Tag) extends Table[Conversation](tag, Some("eda"), "conversation") {
    def id: Rep[String] = column[String]("id", O.PrimaryKey)
    def conversation: Rep[Option[Json]] = column[Option[Json]]("conversation")
    def * : ProvenShape[Conversation] = (id, conversation) <> (Conversation.tupled, Conversation.unapply)
  }

  val conversations = TableQuery[ConversationId]
}
