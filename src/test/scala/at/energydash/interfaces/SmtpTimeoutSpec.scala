package at.energydash.interfaces

import at.energydash.admin.mail.{SendMailReply, SendMailRequest}
import at.energydash.mailer.ConfiguredMailer
import at.energydash.service.SendMailServiceImpl
import com.google.protobuf.ByteString
import com.typesafe.config.{Config, ConfigException, ConfigFactory, ConfigResolveOptions}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.{BufferedReader, InputStreamReader, OutputStream}
import java.net.{InetAddress, ServerSocket}
import java.nio.charset.StandardCharsets
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try

/** SMTP timeouts of the admin mail session (gRPC `SendMailService`, caller: backend with a 30 s deadline). */
class SmtpTimeoutSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  // reference.conf without the process environment, so a set SMTP_ADMIN_* cannot change the result.
  private def adminConfig(env: Map[String, String]): Config =
    ConfigFactory.parseMap(env.asJava).withFallback(ConfigFactory.parseResources("reference.conf"))
      .resolve(ConfigResolveOptions.noSystem()).getConfig("epmsmail.admin")

  private def timeouts(admin: Config): Seq[String] = {
    val props = ConfiguredMailer.getAdminSession(admin).getProperties
    Seq("connectiontimeout", "timeout", "writetimeout").map(k => props.getProperty(s"mail.smtp.$k"))
  }

  "The admin mail session" should {
    "carry the default SMTP timeouts from reference.conf" in {
      timeouts(adminConfig(Map.empty)) shouldBe Seq("10000", "20000", "20000")
    }
    "take the timeouts from the environment variables" in {
      timeouts(adminConfig(Map("SMTP_ADMIN_CONNECTION_TIMEOUT" -> "3000", "SMTP_ADMIN_TIMEOUT" -> "4000",
        "SMTP_ADMIN_WRITE_TIMEOUT" -> "5000"))) shouldBe Seq("3000", "4000", "5000")
    }
    "read durations with a unit and reject an invalid value" in {
      timeouts(adminConfig(Map("SMTP_ADMIN_CONNECTION_TIMEOUT" -> "3s", "SMTP_ADMIN_TIMEOUT" -> "1m",
        "SMTP_ADMIN_WRITE_TIMEOUT" -> "500ms"))) shouldBe Seq("3000", "60000", "500")
      a[ConfigException] should be thrownBy timeouts(adminConfig(Map("SMTP_ADMIN_TIMEOUT" -> "soon")))
    }
  }

  "SendMailService" should {
    "answer 504 SMTP timeout when the relay accepts the connection but never greets" in {
      val (reply, elapsed) = sendThrough(FakeRelay.HangOnGreeting)
      reply.status shouldBe 504
      reply.message.getOrElse("") should startWith("SMTP timeout")
      reply.message.getOrElse("") should not include "member@email.com"
      elapsed should be < 5.seconds
    }
    "answer 504 SMTP timeout when the relay does not answer the DATA command (body not sent)" in {
      val (reply, _) = sendThrough(FakeRelay.HangOnData)
      reply.status shouldBe 504
      reply.message.getOrElse("") should startWith("SMTP timeout")
    }
    "answer 202 SMTP unconfirmed when the relay read the whole message but never answers the final dot" in {
      val (reply, elapsed) = sendThrough(FakeRelay.HangOnDot)
      reply.status shouldBe 202
      reply.message.getOrElse("") should startWith("SMTP unconfirmed")
      reply.message.getOrElse("") should not include "member@email.com"
      elapsed should be < 5.seconds
    }
    "answer 200 without waiting when the relay accepted the message but does not answer QUIT" in {
      val (reply, elapsed) = sendThrough(FakeRelay.HangOnQuit, timeoutMs = 3000)
      reply.status shouldBe 200
      elapsed should be < 2.seconds
    }
  }

  /** Sends one mail through the real SMTP transport of the admin session to a fake relay. */
  private def sendThrough(mode: FakeRelay.Mode, timeoutMs: Int = 1000): (SendMailReply, FiniteDuration) = {
    val relay = new FakeRelay(mode)
    try {
      val admin = ConfigFactory.parseString(
        s"""javaxmail.mail {
           |  transport.protocol.rfc822 = smtp
           |  smtp { host = "127.0.0.1", port = ${relay.port}, connectiontimeout = $timeoutMs, timeout = $timeoutMs, writetimeout = $timeoutMs }
           |}""".stripMargin)
      val service = new SendMailServiceImpl(ConfiguredMailer.getAdminSession(admin))(system)
      val started = System.nanoTime()
      val reply = Await.result(service.sendMail(SendMailRequest(sender = "x", recipient = "member@email.com",
        subject = "Willkommen", body = Some(ByteString.copyFromUtf8("<p>Hallo</p>")))), 15.seconds)
      (reply, (System.nanoTime() - started).nanos)
    } finally relay.close()
  }
}

/** A minimal SMTP relay on loopback for one connection that stops answering at the given point. */
private class FakeRelay(mode: FakeRelay.Mode) {
  private val server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
  val port: Int = server.getLocalPort

  private val thread = new Thread(() => {
    Try {
      val socket = server.accept()
      val in = new BufferedReader(new InputStreamReader(socket.getInputStream, StandardCharsets.US_ASCII))
      val out: OutputStream = socket.getOutputStream
      def reply(line: String): Unit = { out.write(s"$line\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush() }
      if (mode != FakeRelay.HangOnGreeting) {
        reply("220 fake ESMTP")
        var inData = false
        Iterator.continually(in.readLine()).takeWhile(_ != null).foreach { line =>
          if (inData) {
            if (line == ".") { // the end of the message: no reply, except HangOnQuit
              inData = false
              if (mode == FakeRelay.HangOnQuit) reply("250 queued")
            }
          } else line.takeWhile(_ != ' ').toUpperCase match {
            case "DATA" if mode == FakeRelay.HangOnData => ()
            case "DATA" => reply("354 go ahead"); inData = true
            case "QUIT" if mode == FakeRelay.HangOnQuit => ()
            case "QUIT" => reply("221 bye")
            case _ => reply("250 ok")
          }
        }
      }
      try Thread.sleep(10000) catch { case _: InterruptedException => () } // keep the connection open
    }
    ()
  })
  thread.setDaemon(true)
  thread.start()

  def close(): Unit = { server.close(); thread.interrupt() }
}

private object FakeRelay {
  sealed trait Mode
  case object HangOnGreeting extends Mode
  case object HangOnData extends Mode
  case object HangOnDot extends Mode
  case object HangOnQuit extends Mode
}
