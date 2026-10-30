package at.energydash.interfaces

import at.energydash.actors.AdminServer
import at.energydash.admin.mail.SendMailRequest
import at.energydash.mailer.ConfiguredMailer
import at.energydash.service.SendMailServiceImpl
import com.google.protobuf.ByteString
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

import java.net.{InetAddress, ServerSocket}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** SMTP timeouts of the admin mail session (gRPC `SendMailService`, caller: backend with a 30 s deadline). */
class SmtpTimeoutSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  "The admin mail session" should {
    "carry the default SMTP timeouts from reference.conf" in {
      val props = AdminServer.mailSession.getProperties
      props.get("mail.smtp.connectiontimeout").toString shouldBe "10000"
      props.get("mail.smtp.timeout").toString shouldBe "20000"
      props.get("mail.smtp.writetimeout").toString shouldBe "20000"
    }
    "take the timeouts from the environment variables" in {
      val env = ConfigFactory.parseMap(Map(
        "SMTP_ADMIN_CONNECTION_TIMEOUT" -> "3000",
        "SMTP_ADMIN_TIMEOUT" -> "4000",
        "SMTP_ADMIN_WRITE_TIMEOUT" -> "5000").asJava)
      // ${?VAR} is looked up in the config first, then in the process environment.
      val admin = env.withFallback(ConfigFactory.parseResources("reference.conf")).resolve().getConfig("epmsmail.admin")
      val props = ConfiguredMailer.getAdminSession(admin).getProperties
      props.get("mail.smtp.connectiontimeout").toString shouldBe "3000"
      props.get("mail.smtp.timeout").toString shouldBe "4000"
      props.get("mail.smtp.writetimeout").toString shouldBe "5000"
    }
  }

  "SendMailService" should {
    "answer 504 SMTP timeout when the relay accepts the connection but never answers" in {
      // The OS completes the TCP handshake from the backlog; nobody reads or writes: a hanging relay.
      val relay = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
      try {
        // mail.smtp.class: the real SMTP transport instead of mock-javamail's on the test classpath.
        val admin = ConfigFactory.parseString(
          s"""javaxmail.mail {
             |  transport.protocol.rfc822 = smtp
             |  smtp {
             |    class = "com.sun.mail.smtp.SMTPTransport"
             |    host = "127.0.0.1"
             |    port = ${relay.getLocalPort}
             |    connectiontimeout = 1000
             |    timeout = 1000
             |    writetimeout = 1000
             |  }
             |}""".stripMargin)
        val service = new SendMailServiceImpl(ConfiguredMailer.getAdminSession(admin))(system)

        val started = System.nanoTime()
        val reply = Await.result(service.sendMail(SendMailRequest(sender = "x", recipient = "member@email.com",
          subject = "Willkommen", body = Some(ByteString.copyFromUtf8("<p>Hallo</p>")))), 15.seconds)
        val elapsed = (System.nanoTime() - started).nanos

        reply.status shouldBe 504
        reply.message.getOrElse("") should startWith("SMTP timeout")
        reply.message.getOrElse("") should not include "member@email.com"
        elapsed should be < 10.seconds
      } finally relay.close()
    }
  }
}
