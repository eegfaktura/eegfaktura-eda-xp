package at.energydash.domain.dao

import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

import scala.concurrent.{ExecutionContext, Future}

trait TenantConfigRepository {
  def all(): Future[Seq[TenantConfig]]
  def byTenant(tenant: String): Future[Option[TenantConfig]]
  def allActivated(): Future[Seq[TenantConfig]]
  def create(tenant: TenantConfig): Future[TenantConfig]
//
//  def update(id: Int, updateInquest: UpdateInquest): Future[TenantConfig]

}

object TenantConfigRepository {
  final case class TenantNotFound(id: Int) extends Exception(s"Tenant with id $id not found.")
}

class SlickTenantConfigRepository(databaseConfig: DatabaseConfig[PostgresProfile])(implicit ec: ExecutionContext)
  extends TenantConfigRepository with TenentConfigTable {

  import PostgresProfiler.api._

  override def all(): Future[Seq[TenantConfig]] = databaseConfig.db.run(tenantConfigs.result)

  override def allActivated(): Future[Seq[TenantConfig]] = {
    val q = tenantConfigs.filter(_.active === true)
    databaseConfig.db.run(q.result)
  }

  override def byTenant(tenant: String): Future[Option[TenantConfig]] = {
    val q = tenantConfigs.filter(_.tenant === tenant).take(1)
    databaseConfig.db.run(q.result).map(_.headOption)
  }

  override def create(tenantConfig: TenantConfig): Future[TenantConfig] = /*db.run {
    (tenantConfigs returning tenantConfigs.map(_.tenant) into ((_, tenant) => tenantConfig.copy(tenant = tenant))) += tenantConfig
  }*/
    databaseConfig.db.run(tenantConfigs += tenantConfig).map(_=>tenantConfig)


  def init() = {
    databaseConfig.db.run(DBIO.seq(/*sqlu"""create schema eda;SET SCHEMA eda;""",*/tenantConfigs.schema.create))
  }
}

