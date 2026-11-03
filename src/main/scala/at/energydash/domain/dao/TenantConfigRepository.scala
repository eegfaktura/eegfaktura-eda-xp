package at.energydash.domain.dao

import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

import scala.concurrent.{ExecutionContext, Future}

trait TenantConfigRepository {
  def allActivated(cType: Option[String]): Future[Seq[TenantConfig]]
  def create(tenant: TenantConfig): Future[TenantConfig]
  def update(tenant: TenantConfig): Future[Option[TenantConfig]]
}

class SlickTenantConfigRepository(databaseConfig: DatabaseConfig[PostgresProfile])(implicit ec: ExecutionContext)
  extends TenantConfigRepository with TenentConfigTable {

  import PostgresProfiler.api._

  override def allActivated(cType: Option[String] = None): Future[Seq[TenantConfig]] = {
    val q = tenantConfigs.filter(c => c.active === true)
    val qq = cType match {
      case Some(t) => q.filter(_.cType=== t)
      case _ => q
    }
    databaseConfig.db.run(qq.result)
  }

  override def create(tenantConfig: TenantConfig): Future[TenantConfig] =
      databaseConfig.db.run(
          (tenantConfigs returning tenantConfigs).insertOrUpdate(tenantConfig).map(_.head)
      )

  override def update(tenantConfig: TenantConfig): Future[Option[TenantConfig]] =
    databaseConfig.db.run((tenantConfigs returning tenantConfigs).insertOrUpdate(tenantConfig))
}

