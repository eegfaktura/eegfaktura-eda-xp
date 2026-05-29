package at.energydash.domain.dao

import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

import scala.concurrent.{ExecutionContext, Future}

trait TenantConfigRepository {
  def all(): Future[Seq[TenantConfig]]
  def byTenant(tenant: String): Future[Option[TenantConfig]]
  def allActivated(cType: Option[String]): Future[Seq[TenantConfig]]
  def isActivated(cType: String, tenant: String): Future[Option[TenantConfig]]
  def create(tenant: TenantConfig): Future[TenantConfig]
  def update(tenant: TenantConfig): Future[Option[TenantConfig]]
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

  override def allActivated(cType: Option[String] = None): Future[Seq[TenantConfig]] = {
    val q = tenantConfigs.filter(c => c.active === true)
    val qq = cType match {
      case Some(t) => q.filter(_.cType=== t)
      case _ => q
    }
    databaseConfig.db.run(qq.result)
  }

  override def isActivated(cType: String, tenant: String): Future[Option[TenantConfig]] = {
    databaseConfig.db.run(tenantConfigs.filter(c => c.active=== true && c.cType=== cType &&c.tenant=== tenant).take(1).result.headOption)
  }

  override def byTenant(tenant: String): Future[Option[TenantConfig]] =
    databaseConfig.db.run(tenantConfigs.filter(_.tenant === tenant).take(1).result).map(_.headOption)

  override def create(tenantConfig: TenantConfig): Future[TenantConfig] =
//    databaseConfig.db.run(tenantConfigs += tenantConfig).map(_=>tenantConfig)
      databaseConfig.db.run(
          (tenantConfigs returning tenantConfigs).insertOrUpdate(tenantConfig).map(_.head)
      )

  override def update(tenantConfig: TenantConfig): Future[Option[TenantConfig]] =
    databaseConfig.db.run((tenantConfigs returning tenantConfigs).insertOrUpdate(tenantConfig))


  def init() = {
    databaseConfig.db.run(DBIO.seq(/*sqlu"""create schema eda;SET SCHEMA eda;""",*/tenantConfigs.schema.create))
  }
}

