package at.energydash.mailer
import at.energydash.config.{ConfigExtensions, Config => AppConfig}
import at.energydash.domain.dao.TenantConfig
import com.typesafe.config.Config
import courier.Mailer

import java.util.Properties
import javax.mail.{PasswordAuthentication, Provider, Session}

object ConfiguredMailer {

  var sessionStore: Map[String, Session] = Map.empty

  def getSession(config: TenantConfig): Session = {
    sessionStore.get(config.tenant) match {
      case Some(session) => session
      case None => {
        val session = createSession(config)
        sessionStore = sessionStore ++ Seq(config.tenant -> session)
        session
      }
    }
  }

  def createSession(config: TenantConfig): Session = {
    //First convert the config to a java.util.Properties
    import scala.jdk.CollectionConverters._

    val properties = new Properties()

    val mergedMap:Map[String, Object] = config.toMap ++ AppConfig.getDomain(config.domain.getOrElse(""))

    properties.putAll(mergedMap.asJava)
    // Message-ID uses this address' domain (eegfaktura.at) instead of the pod hostname (deliverability).
    properties.put("mail.from", AppConfig.adminMailFrom)
//    println(s"Mail Properties: ${properties}")
    def authenticatorFromConfig(user: String, pass: String) = {
      new javax.mail.Authenticator() {
        override def getPasswordAuthentication() = new PasswordAuthentication(user, pass)
      }
    }

    def getOptionConfigured[T](user: String, pass: String, constructor: (String, String) => T): Option[T] = {
     Some(constructor(user, pass))
    }
    val configAuthenticator = getOptionConfigured(config.toAuthMap.getOrElse("username", ""), config.toAuthMap.getOrElse("password", ""), authenticatorFromConfig)
    //Then make the session
    val session = configAuthenticator.fold(Session.getInstance(properties))(
      authenticator => Session.getInstance(properties, authenticator))
    session
  }

  def createMailerFromSession(session: Session): Mailer = {
    Mailer(session)
  }

  def getAdminSession(config: Config): Session = {
    //First convert the config to a java.util.Properties
    import scala.jdk.CollectionConverters._

    val properties = new Properties()
    val map = config.getConfig("javaxmail").entrySet().asScala.map(e => e.getKey -> e.getValue.unwrapped()).toMap

    properties.putAll(map.asJava)
    properties.put("mail.from", AppConfig.adminMailFrom)
    // SMTP timeouts as HOCON durations ("20000" = milliseconds, or "20s"); JavaMail itself would read "20s"
    // as no timeout. An invalid value fails at start-up.
    Seq("connectiontimeout", "timeout", "writetimeout").foreach { key =>
      val path = s"javaxmail.mail.smtp.$key"
      if (config.hasPath(path)) properties.put(s"mail.smtp.$key", config.getDuration(path).toMillis.toString)
    }
    // QUIT is still sent, but its reply is not awaited: the mail is accepted at that point, and a relay
    // that does not answer QUIT must not turn a delivered mail into a timeout.
    properties.put("mail.smtp.quitwait", "false")

    def authenticatorFromConfig(config: Config) = {
      new javax.mail.Authenticator() {
        override def getPasswordAuthentication() = new PasswordAuthentication(config.getString("username"), config.getString("password"))
      }
    }

    val configAuthenticator = config.getOptionConfigured("authenticator", authenticatorFromConfig)

    //Then make the session
    val session = configAuthenticator.fold(Session.getInstance(properties))(
      authenticator => Session.getInstance(properties, authenticator))
    // smtp: tells a timeout after the complete message (unconfirmed) from one before it (not sent).
    session.setProvider(new Provider(Provider.Type.TRANSPORT, "smtp", classOf[UnconfirmedAwareSmtpTransport].getName, "eegfaktura", null))
    session
  }
}
