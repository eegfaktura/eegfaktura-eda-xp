package at.energydash.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import at.energydash.actors.MqttPublisher.{MqttCommand, MqttPublish}
import at.energydash.actors.TenantMailActor.FetchEmailCommand
import at.energydash.domain.dao.{Db, SlickEmailOutboxRepository, TenantConfig}
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.mailer.ConfiguredMailer
import at.energydash.testsupport.LogCapture
import courier.Multipart
import org.jvnet.mock_javamail.Mailbox
import org.scalatest.wordspec.AnyWordSpecLike

import javax.mail.Message
import javax.mail.internet.{InternetAddress, MimeMessage}
import scala.io.Source
import scala.xml.XML

class FetchMailActorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with at.energydash.EmbeddedDb {

  implicit def stringToInternetAddress(string:String):InternetAddress = new InternetAddress(string)
  import scala.concurrent.ExecutionContext.Implicits.global

  val tenantConfig = TenantConfig("myeeg", "KEP", Some("email.com"), Some("email.com"), Some(0), Some("smtp.mail.com"), Some(0), Some("sepp"), Some("password"), Some(""), Some(""), true)
  val emailRepo = new SlickEmailOutboxRepository(Db.config)
  val rand = new scala.util.Random

  def perpareEmail(tenant: String, xmlfile: String, subject: String): Unit = {
    val session = ConfiguredMailer.createSession(tenantConfig)

    // prepare Mock - Mailbox
    val attachement = XML.load(Source.fromResource(xmlfile).reader())

    val mailMsg: Message = new MimeMessage(session)
    mailMsg.setSubject(subject)
    mailMsg.setHeader("Message-ID", rand.nextLong(100000).toString)
    mailMsg.setFrom("test@email.com")
    mailMsg.setContent(Multipart()
      .attachBytes(
        attachement.toString().getBytes, "test.xml", "text/xml")
      .html("Hallo").parts)

    Mailbox.get("sepp@email.com").add(mailMsg)
  }

  def perpareErrorEmail(tenant: String): Unit = {
    val session = ConfiguredMailer.createSession(tenantConfig)

    val mailMsg: Message = new MimeMessage(session)
    mailMsg.setSubject("Just a Mail")
    mailMsg.setHeader("Message-ID", "1")
    mailMsg.setFrom("test@email.com")
    mailMsg.setContent(Multipart()
      .html("Hallo").parts)

    Mailbox.get("sepp@email.com").add(mailMsg)
  }

  "Fetch Mail Actor" should {
    // dev#45 L4: the exception of a failed attachment parse can hold the whole document
    "log a failed attachment parse without the document" in {
      val meter = "AT0030000000000000000000000526178"
      val session = ConfiguredMailer.createSession(tenantConfig)
      val mail: Message = new MimeMessage(session)
      mail.setSubject("[ZUSTIMMUNG_ECON_01.00 MessageId=123456]")
      mail.setHeader("Message-ID", "4711")
      mail.setFrom("test@email.com")
      val document = Source.fromResource("ZUSTIMMUNG_ECON.xml").mkString
        .replaceFirst("(?s)<([a-z0-9]+:)?ProcessDirectory.*ProcessDirectory>", s"<ProcessDirectory><Meter>$meter</Meter></ProcessDirectory>")
      mail.setContent(Multipart().attachBytes(document.getBytes, "test.xml", "text/xml").html("Hallo").parts)
      Mailbox.get("sepp@email.com").add(mail)

      try {
        val (_, events) = LogCapture("at.energydash.mailer.Fetcher") {
          at.energydash.mailer.Fetcher().fetch("", _ => fail("the attachment must not parse"))(
            at.energydash.mailer.Fetcher.FetcherContext("myeeg", session, emailRepo))
        }
        val failed = events.filter(_.getLevel.toString == "ERROR")
        failed should have size 1
        failed.head.getFormattedMessage should startWith("Extract Message failed for mail ")
        failed.head.getThrowableProxy should not be null
        at.energydash.logging.EcsJson.line(failed.head, "eegfaktura-eda-xp", "dev") should not include meter
      } finally Mailbox.clearAll()
    }

    "log the message id of a subject only if it has the shape of one, and return it as before" in {
      val fetcher = at.energydash.mailer.Fetcher()
      val odd = "Max Mustermann, max@example.com"
      val (parsed, events) = LogCapture("at.energydash.mailer.Fetcher") {
        (fetcher.parseSubject("[CR_MSG_03.03 MessageId=RC100130.2023-1]"), fetcher.parseSubject(s"[CR_MSG_03.03 MessageId=$odd]"))
      }
      parsed shouldBe ((Some(("CR_MSG", "03.03", "RC100130.2023-1")), Some(("CR_MSG", "03.03", odd))))
      LogCapture.messages(events) shouldBe List("Mail Subject: Protocol: CR_MSG Version: 03.03 MessageId: RC100130.2023-1",
        "Mail Subject: Protocol: CR_MSG Version: 03.03 MessageId: (not a message id, not logged)")
    }

    // dev#45 L5
    "log no address when sending a mail" in {
      val data = at.energydash.domain.EbMsMessage(conversationId = "C4711", sender = "RC100001", receiver = "AT003000",
        messageCode = EbMsMessageType.ERROR_MESSAGE)
      val email = at.energydash.mailer.EmailService.EmailModel("RC100001", "AT003000", "subject", org.apache.pekko.util.ByteString("<a/>"), data)
      val command = at.energydash.mailer.EmailService.SendEmailCommand(email, "email.com", createTestProbe[EdaCommand]().ref)
      try {
        val (_, events) = LogCapture("at.energydash.mailer.EmailService$") {
          command.sendEmail(courier.Mailer(ConfiguredMailer.createSession(tenantConfig)))
        }
        LogCapture.messages(events) shouldBe List(s"About to send Email ${EbMsMessageType.ERROR_MESSAGE}, conversation C4711")
      } finally Mailbox.clearAll()
    }

    "handle Email Success Messages" in {
      val mailActor = spawn(TenantMailActor(tenantConfig, emailRepo))
      val replyActor = createTestProbe[MqttCommand]()

      perpareEmail("myeeg", "TestECMPLIst.xml", "[EC_PODLIST_01.00 MessageId=123456]")

      mailActor ! FetchEmailCommand("myeeg", "", replyActor.ref)
      val notification = replyActor.expectMessageType[MqttPublish]
      notification.mails.length shouldBe 1
      notification.mails.head.message.messageCode shouldBe EbMsMessageType.ZP_LIST_RESPONSE

      Mailbox.clearAll()
    }

    // XP-CQ-20: a failed mailbox connect was dropped without a trace
    "log a failed mailbox connect" in {
      val props = new java.util.Properties()
      props.put("mail.store.protocol", "no-such-protocol")
      val broken = at.energydash.mailer.Fetcher.FetcherContext("myeeg", javax.mail.Session.getInstance(props), emailRepo)
      val (_, events) = LogCapture("at.energydash.actors.TenantMailActor") {
        FetchEmailCommand("myeeg", "", createTestProbe[MqttCommand]().ref)
          .fetchEmail("", _ => fail("there is no mailbox to read"))(scala.concurrent.ExecutionContext.global, broken)
      }
      events.map(_.getLevel.toString) shouldBe List("ERROR")
      events.head.getFormattedMessage should startWith("Fetching mails failed: ")
      events.head.getThrowableProxy.getClassName shouldBe classOf[javax.mail.NoSuchProviderException].getName
    }

    // XP-CQ-20 (review): a failing close after a failed fetch must not hide the fetch's exception
    "keep the fetch failure when closing the mailbox fails as well" in {
      val original = new IllegalStateException("search failed")
      val thrown = the[IllegalStateException] thrownBy
        at.energydash.mailer.Fetcher.closing(throw new javax.mail.MessagingException("close failed"))(throw original)
      thrown should be theSameInstanceAs original
      thrown.getSuppressed.toList.map(_.getMessage) shouldBe List("close failed")
      a[javax.mail.MessagingException] should be thrownBy
        at.energydash.mailer.Fetcher.closing(throw new javax.mail.MessagingException("close failed"))(())
    }

    // XP-CQ-27: a worker for a changed tenant config must not get the session of the earlier config
    "read the mailbox of the current config after the tenant config changed" in {
      // a tenant of its own: no earlier test may have created a session for it
      val changed = tenantConfig.copy(tenant = "cq27-changed-config")
      val earlier = spawn(TenantMailActor(changed.copy(user = Some("someone-else")), emailRepo))
      val mailActor = spawn(TenantMailActor(changed, emailRepo))
      val replyActor = createTestProbe[MqttCommand]()
      try {
        perpareEmail(changed.tenant, "TestECMPLIst.xml", "[EC_PODLIST_01.00 MessageId=123456]")

        mailActor ! FetchEmailCommand(changed.tenant, "", replyActor.ref)
        replyActor.expectMessageType[MqttPublish].mails should have size 1
      } finally {
        testKit.stop(earlier); testKit.stop(mailActor)
        Mailbox.clearAll()
      }
    }

    "handle Error EmailMessages" in {
      val mailActor = spawn(TenantMailActor(tenantConfig, emailRepo))
      val replyActor = createTestProbe[MqttCommand]()

      perpareErrorEmail("myeeg")

      mailActor ! FetchEmailCommand("myeeg", "", replyActor.ref)

      val errMsg = replyActor.expectMessageType[MqttPublish]
      errMsg.mails.length shouldBe 1
      errMsg.mails.head.message.messageCode shouldBe EbMsMessageType.ERROR_MESSAGE

      Mailbox.clearAll()
    }

    "handle ERROR XML from EDA" in {
      val mailActor = spawn(TenantMailActor(tenantConfig, emailRepo))
      val replyActor = createTestProbe[MqttCommand]()

      perpareEmail("myeeg", "error.xml", "EDA Mail Adapter - Fehler")

      mailActor ! FetchEmailCommand("myeeg", "", replyActor.ref)
      val mqttMsg = replyActor.expectMessageType[MqttPublish]
      mqttMsg.mails.length shouldBe 1
      mqttMsg.mails.head.message.errorMessage.get.strip() shouldBe "Der Messenger hat eine Fehlermeldung zurueck geliefert. Beschreibung: 'Validation failed.  Reason: 2 XML errors found while validating with (ANFORDERUNG_ECP 01.00/EC_PODLIST_01.00)\n   (Line 2 / Column 1154) cvc-minLength-valid: Value &apos;&apos; with length = &apos;0&apos; is not facet-valid with respect to minLength &apos;1&apos; for type &apos;MeteringPoint&apos;.\n   (Line 2 / Column 1154) cvc-type.3.1.3: The value &apos;&apos; of element &apos;ct:MeteringPoint&apos; is not valid.'"

      Mailbox.clearAll()
    }

    "response with existing conversation id" in {

      val mailActor = spawn(TenantMailActor(tenantConfig, emailRepo))
      val replyActor = createTestProbe[MqttCommand]()

      perpareEmail("myeeg", "ANTWORT_PT.xml", "[CR_REQ_PT_03.00 MessageId=123456678]")

      mailActor ! FetchEmailCommand("myeeg", "", replyActor.ref)

      val mqttMsg = replyActor.expectMessageType[MqttPublish]

      mqttMsg.mails.length shouldBe 1
      mqttMsg.mails.head.message.responseData.get.head.ResponseCode.head shouldBe 70

      Mailbox.clearAll()
    }

    "response with not existing conversation id" in {

      val mailActor = spawn(TenantMailActor(tenantConfig, emailRepo))
      val replyActor = createTestProbe[MqttCommand]()

      perpareEmail("myeeg", "ANTWORT_PT.xml", "[CR_REQ_PT_03.00 MessageId=123456678]")

      mailActor ! FetchEmailCommand("myeeg", "", replyActor.ref)

      val mqttMsg = replyActor.expectMessageType[MqttPublish]

      mqttMsg.mails.head.message.meter shouldBe None
      mqttMsg.mails.head.message.responseData.get.head.ResponseCode.head shouldBe 70

      Mailbox.clearAll()
    }

    "response with existing conversation id but with all infos" in {

      val mailActor = spawn(TenantMailActor(tenantConfig, emailRepo))
      val replyActor = createTestProbe[MqttCommand]()

      perpareEmail("myeeg", "ZUSTIMMUNG_ECON.xml", "[EC_REQ_ONL_01.00 MessageId=123456678]")

      mailActor ! FetchEmailCommand("myeeg", "", replyActor.ref)

      val mqttMsg = replyActor.expectMessageType[MqttPublish]
      mqttMsg.mails.head.message.meter shouldBe None
      mqttMsg.mails.head.message.responseData.get.head.ResponseCode.head shouldBe 175
      mqttMsg.mails.head.message.responseData.get.head.MeteringPoint shouldBe Some("AT0030000000000000000000000959561")

      Mailbox.clearAll()
    }
  }

}
