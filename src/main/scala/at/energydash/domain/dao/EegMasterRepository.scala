package at.energydash.domain.dao

import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

import scala.concurrent.{ExecutionContext, Future}

trait EegMasterRepository {
  def all(): Future[Seq[EegMaster]]
  def byTenant(tenant: String): Future[Option[EegMaster]]
}

class SlickEegMasterRepository(databaseConfig: DatabaseConfig[PostgresProfile])(implicit ec: ExecutionContext)
  extends EegMasterRepository with EegMasterTable {
  import PostgresProfiler.api._

  override def all(): Future[Seq[EegMaster]] = databaseConfig.db.run(eegMasters.result)

  override def byTenant(tenant: String): Future[Option[EegMaster]] = {
    val q = eegMasters.filter(_.tenant === tenant)
    databaseConfig.db.run(q.result).map {
      case rs: Seq[EegMaster] if rs.size == 1 => rs.headOption
      case _ => None
    }
  }
}
