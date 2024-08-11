package at.energydash.domain.dao

case class TenantConfig(tenant: String, domain: String, host: String, imapPort: Int, smtpHost: String, smtpPort: Int, user: String, passwd: String, imapSecurity: String, smtpSecurity: String, active: Boolean) {
  import java.lang.Boolean._
  def toMap(): Map[String, AnyRef] =
    Map[String, AnyRef](
      "mail.store.protocol" -> "imap",
      "mail.imap.host" -> host,
      "mail.imap.user" -> user,
      "mail.imap.port" -> java.lang.Integer.valueOf(imapPort),
      "mail.imap.ssl.trust" -> host,
      "mail.smtp.host" -> smtpHost,
      "mail.smtp.user" -> user,
      "mail.smtp.port" -> java.lang.Integer.valueOf(smtpPort),
      "mail.smtp.auth" -> TRUE) ++
      (if (smtpSecurity.toUpperCase() == "SSL") Seq("mail.smtp.ssl.enable" -> TRUE) else Nil) ++
      (if (smtpSecurity.toUpperCase() == "STARTTLS") Seq("mail.smtp.starttls.enable" -> TRUE) else Nil) ++
      (if (imapSecurity.toUpperCase() == "SSL") Seq("mail.imap.ssl.enable" -> TRUE) else Nil) ++
      (if (imapSecurity.toUpperCase() == "STARTTLS") Seq("mail.imap.starttls.enable" -> TRUE) else Nil)

  def toAuthMap() = Map("username" -> user, "password" -> passwd)
}
