package at.energydash.domain.dao

import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

import scala.concurrent.{ExecutionContext, Future}

trait EmailOutboxRepository {
  def all(): Future[Seq[EmailOutbox]]
  def byTenant(tenant: String): Future[Option[EmailOutbox]]
  def create(email: EmailOutbox): Future[Int]
  //
  //  def update(id: Int, updateInquest: UpdateInquest): Future[TenantConfig]

}

object EmailOutboxRepository {
  final case class MailNotFound(id: String) extends Exception(s"Mail with tenant $id not found.")
}

class SlickEmailOutboxRepository(databaseConfig: DatabaseConfig[PostgresProfile])(implicit ec: ExecutionContext)
  extends EmailOutboxRepository with EmailOutboxTable {

  import PostgresProfiler.api._

  override def all(): Future[Seq[EmailOutbox]] = databaseConfig.db.run(emailOutboxs.result)

  override def byTenant(tenant: String): Future[Option[EmailOutbox]] = {
    val q = emailOutboxs.filter(_.tenant === tenant).take(1)
    databaseConfig.db.run(q.result).map(_.headOption)
  }

  override def create(email: EmailOutbox): Future[Int] = {
//    val q = (
//      emailOutboxs returning emailOutboxs.map(_.tenant) into ((_, id) => email.copy(id = id))
//      ) += email
    val q = emailOutboxs += email
    databaseConfig.db.run(q)
  }

  def init(): Unit = {
    databaseConfig.db.run(DBIO.seq(emailOutboxs.schema.createIfNotExists))
  }
}

