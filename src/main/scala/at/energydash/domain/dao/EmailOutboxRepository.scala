package at.energydash.domain.dao

import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

import scala.concurrent.{ExecutionContext, Future}

trait EmailOutboxRepository {
  def create(email: EmailOutbox): Future[Int]
}

class SlickEmailOutboxRepository(databaseConfig: DatabaseConfig[PostgresProfile])(implicit ec: ExecutionContext)
  extends EmailOutboxRepository with EmailOutboxTable {

  import PostgresProfiler.api._

  override def create(email: EmailOutbox): Future[Int] = {
    val q = emailOutboxs += email
    databaseConfig.db.run(q)
  }
}

