package at.energydash.domain.dao

import at.energydash.domain.EbMsMessage
import io.circe.generic.auto._
import io.circe.syntax.{EncoderOps, _}
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

import scala.concurrent.{ExecutionContext, Future}

trait ConversationRepository {
  def findById(id: String): Future[Option[Conversation]]
  def create(message: EbMsMessage): Future[Int]
}

class SlickConversationRepository(databaseConfig: DatabaseConfig[PostgresProfile])(implicit ec: ExecutionContext)
  extends ConversationRepository with ConversationTable {

  import PostgresProfiler.api._
  import at.energydash.domain.JsonImplicit._

  override def findById(id: String): Future[Option[Conversation]] =
    databaseConfig.db.run(conversations.filter(_.id === id).result.headOption)

  override def create(message: EbMsMessage): Future[Int] =
    databaseConfig.db.run(conversations += Conversation(message.conversationId, Some(message.asJson)))
}
