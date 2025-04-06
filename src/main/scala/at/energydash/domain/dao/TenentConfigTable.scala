package at.energydash.domain.dao

import slick.lifted.ProvenShape

trait TenentConfigTable {

  import PostgresProfiler.api._

  class TenantConfigs(tag: Tag) extends Table[TenantConfig](tag, Some("eda"), "tenantconfig") {
    def tenant: Rep[String] = column[String]("tenant", O.PrimaryKey)
    def cType: Rep[String] = column[String]("type")
    def domain: Rep[Option[String]] = column[Option[String]]("domain")
    def host: Rep[Option[String]] = column[Option[String]]("host")
    def imapPort: Rep[Option[Int]] = column[Option[Int]]("imapport")
    def smtpHost: Rep[Option[String]] = column[Option[String]]("smtphost")
    def smtpPort: Rep[Option[Int]] = column[Option[Int]]("smtpport")
    def user: Rep[Option[String]] = column[Option[String]]("username")
    def passwd: Rep[Option[String]] = column[Option[String]]("pass")
    def imapSecurity: Rep[Option[String]] = column[Option[String]]("imap_security")
    def smtpSecurity: Rep[Option[String]] = column[Option[String]]("smtp_security")
    def active: Rep[Boolean] = column[Boolean]("active")
    def * : ProvenShape[TenantConfig] = (tenant, cType, domain, host, imapPort, smtpHost, smtpPort, user, passwd, imapSecurity, smtpSecurity, active) <> (TenantConfig.tupled, TenantConfig.unapply)
  }

  val tenantConfigs = TableQuery[TenantConfigs]
}