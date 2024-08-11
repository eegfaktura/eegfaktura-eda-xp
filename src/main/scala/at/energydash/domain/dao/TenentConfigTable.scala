package at.energydash.domain.dao

import slick.lifted.ProvenShape

trait TenentConfigTable {

  import PostgresProfiler.api._

  class TenantConfigs(tag: Tag) extends Table[TenantConfig](tag, Some("eda"), "tenantconfig") {
    def tenant: Rep[String] = column[String]("tenant", O.PrimaryKey)
    def domain: Rep[String] = column[String]("domain")
    def host: Rep[String] = column[String]("host")
    def imapPort: Rep[Int] = column[Int]("imapport")
    def smtpHost: Rep[String] = column[String]("smtphost")
    def smtpPort: Rep[Int] = column[Int]("smtpport")
    def user: Rep[String] = column[String]("username")
    def passwd: Rep[String] = column[String]("pass")
    def imapSecurity: Rep[String] = column[String]("imap_security")
    def smtpSecurity: Rep[String] = column[String]("smtp_security")
    def active: Rep[Boolean] = column[Boolean]("active")
    def * : ProvenShape[TenantConfig] = (tenant, domain, host, imapPort, smtpHost, smtpPort, user, passwd, imapSecurity, smtpSecurity, active) <> (TenantConfig.tupled, TenantConfig.unapply)
  }

  val tenantConfigs = TableQuery[TenantConfigs]
}