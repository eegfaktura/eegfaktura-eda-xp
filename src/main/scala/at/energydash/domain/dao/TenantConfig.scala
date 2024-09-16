package at.energydash.domain.dao

case class TenantConfig(tenant: String, cType: String, domain: Option[String], host: Option[String], imapPort: Option[Int], smtpHost: Option[String], smtpPort: Option[Int], user: Option[String], passwd: Option[String], imapSecurity: Option[String], smtpSecurity: Option[String], active: Boolean) {
  import java.lang.Boolean._
  def toMap: Map[String, AnyRef] =
    Map[String, AnyRef](
      "mail.store.protocol" -> "imap",
      "mail.imap.host" -> host.getOrElse(""),
      "mail.imap.user" -> user.getOrElse(""),
      "mail.imap.port" -> java.lang.Integer.valueOf(imapPort.getOrElse(0)),
      "mail.imap.ssl.trust" -> host.getOrElse(""),
      "mail.smtp.host" -> smtpHost.getOrElse(""),
      "mail.smtp.user" -> user.getOrElse(""),
      "mail.smtp.port" -> java.lang.Integer.valueOf(smtpPort.getOrElse(0)),
      "mail.smtp.auth" -> TRUE) ++
      (if (smtpSecurity.getOrElse("").toUpperCase() == "SSL") Seq("mail.smtp.ssl.enable" -> TRUE) else Nil) ++
      (if (smtpSecurity.getOrElse("").toUpperCase() == "STARTTLS") Seq("mail.smtp.starttls.enable" -> TRUE) else Nil) ++
      (if (imapSecurity.getOrElse("").toUpperCase() == "SSL") Seq("mail.imap.ssl.enable" -> TRUE) else Nil) ++
      (if (imapSecurity.getOrElse("").toUpperCase() == "STARTTLS") Seq("mail.imap.starttls.enable" -> TRUE) else Nil)

  def toAuthMap: Map[String, String] = Map("username" -> user.getOrElse(""), "password" -> passwd.getOrElse(""))
}
