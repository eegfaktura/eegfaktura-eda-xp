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

import java.io.FileInputStream
import java.nio.file.{FileSystems, Files}
import javax.mail.Message
import javax.mail.internet.{InternetAddress, MimeMessage}
//import scala.collection.JavaConverters._
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.xml.XML

class FetchMailActorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with at.energydash.EmbeddedDb {

  implicit def stringToInternetAddress(string:String):InternetAddress = new InternetAddress(string)
  import scala.concurrent.ExecutionContext.Implicits.global

  val tenantConfig = TenantConfig("myeeg", "KEP", Some("email.com"), Some("email.com"), Some(0), Some("smtp.mail.com"), Some(0), Some("sepp"), Some("password"), Some(""), Some(""), true)
  val emailRepo = new SlickEmailOutboxRepository(Db.getConfig)
  val rand = new scala.util.Random

//  override def afterAll: Unit = {
//    TestKit.shutdownActorSystem(system)
//  }
  def loadManyMails(): Unit = {
    val session = ConfiguredMailer.getSession(tenantConfig)

    val dir = FileSystems.getDefault.getPath("/home/petero/projects/energycash/scripts/rc100298-aug")
    val emails = Files.walk(dir).iterator().asScala.filter(Files.isRegularFile(_))
      .map(f => new MimeMessage(session, new FileInputStream(f.toFile)))

    Mailbox.get("sepp@email.com").addAll(emails.toSeq.asJava)
  }

  def perpareEmail(tenant: String, xmlfile: String, subject: String): Unit = {
    val session = ConfiguredMailer.getSession(tenantConfig)

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
    val session = ConfiguredMailer.getSession(tenantConfig)

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
      val session = ConfiguredMailer.getSession(tenantConfig)
      val mail: Message = new MimeMessage(session)
      mail.setSubject("[ZUSTIMMUNG_ECON_01.00 MessageId=123456]")
      mail.setHeader("Message-ID", "4711")
      mail.setFrom("test@email.com")
      val document = Source.fromResource("ZUSTIMMUNG_ECON.xml").mkString
        .replaceFirst("(?s)<([a-z0-9]+:)?ProcessDirectory.*ProcessDirectory>", s"<ProcessDirectory><Meter>$meter</Meter></ProcessDirectory>")
      mail.setContent(Multipart().attachBytes(document.getBytes, "test.xml", "text/xml").html("Hallo").parts)
      Mailbox.get("sepp@email.com").add(mail)

      val (_, events) = LogCapture("at.energydash.mailer.Fetcher") {
        at.energydash.mailer.Fetcher().fetch("", _ => fail("the attachment must not parse"))(
          at.energydash.mailer.Fetcher.FetcherContext("myeeg", session, emailRepo))
      }
      val failed = events.filter(_.getLevel.toString == "ERROR")
      failed should have size 1
      failed.head.getFormattedMessage should startWith("Extract Message failed for mail ")
      failed.head.getThrowableProxy should not be null
      at.energydash.logging.EcsJson.line(failed.head, "eegfaktura-eda-xp", "dev") should not include meter

      Mailbox.clearAll()
    }

    // dev#45 L5
    "log no address when sending a mail" in {
      val data = at.energydash.domain.EbMsMessage(conversationId = "C4711", sender = "RC100001", receiver = "AT003000",
        messageCode = EbMsMessageType.ERROR_MESSAGE)
      val email = at.energydash.mailer.EmailService.EmailModel("RC100001", "AT003000", "subject", org.apache.pekko.util.ByteString("<a/>"), data)
      val command = at.energydash.mailer.EmailService.SendEmailCommand(email, "email.com", createTestProbe[EdaCommand]().ref)
      val (_, events) = LogCapture("at.energydash.mailer.EmailService$") {
        command.sendEmail(courier.Mailer(ConfiguredMailer.getSession(tenantConfig)))
      }
      LogCapture.messages(events) shouldBe List(s"About to send Email ${EbMsMessageType.ERROR_MESSAGE}, conversation C4711")

      Mailbox.clearAll()
    }

    "handle Email Success Messages" in {
      val messageStoreProbe = createTestProbe[EdaCommand]()
      val mailActor = spawn(TenantMailActor(tenantConfig, emailRepo))
      val replyActor = createTestProbe[MqttCommand]()

      perpareEmail("myeeg", "TestECMPLIst.xml", "[EC_PODLIST_01.00 MessageId=123456]")

      mailActor ! FetchEmailCommand("myeeg", "", replyActor.ref)
      val notification = replyActor.expectMessageType[MqttPublish]
      notification.mails.length shouldBe 1
      notification.mails.head.message.messageCode shouldBe EbMsMessageType.ZP_LIST_RESPONSE

      Mailbox.clearAll()
    }

    "handle Error EmailMessages" in {
      val messageStoreProbe = createTestProbe[EdaCommand]()
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

      val messageStoreProbe = createTestProbe[EdaCommand]()
      val mailActor = spawn(TenantMailActor(tenantConfig, emailRepo))
      val replyActor = createTestProbe[MqttCommand]()

      perpareEmail("myeeg", "ANTWORT_PT.xml", "[CR_REQ_PT_03.00 MessageId=123456678]")

      mailActor ! FetchEmailCommand("myeeg", "", replyActor.ref)

//      val storeMsg = messageStoreProbe.expectMessageType[MergeConversation] //(MessageStorage.FindById("RC100130202301231674475740000000030", _))
//      storeMsg.replyTo ! ConversationMerged(
//        EbMsMessage(None, "RC100001202307250000000000000000009", "sender", "receiver", ENERGY_SYNC_RES, messageCodeVersion=Some("01.00"), None, Some(Meter("meterid123456", None))))

      val mqttMsg = replyActor.expectMessageType[MqttPublish]

      mqttMsg.mails.length shouldBe 1
      mqttMsg.mails.head.message.responseData.get.head.ResponseCode.head shouldBe 70

      Mailbox.clearAll()
    }

    "response with not existing conversation id" in {

      val messageStoreProbe = createTestProbe[EdaCommand]()
      val mailActor = spawn(TenantMailActor(tenantConfig, emailRepo))
      val replyActor = createTestProbe[MqttCommand]()

      perpareEmail("myeeg", "ANTWORT_PT.xml", "[CR_REQ_PT_03.00 MessageId=123456678]")

      mailActor ! FetchEmailCommand("myeeg", "", replyActor.ref)

//      val storeMsg = messageStoreProbe.expectMessageType[MergeConversation] //(MessageStorage.FindById("RC100130202301231674475740000000030", _))
//      storeMsg.replyTo ! MergeError("RC100001202307250000000000000000009")

      val mqttMsg = replyActor.expectMessageType[MqttPublish]

      mqttMsg.mails.head.message.meter shouldBe None
      mqttMsg.mails.head.message.responseData.get.head.ResponseCode.head shouldBe 70

      Mailbox.clearAll()
    }

    "response with existing conversation id but with all infos" in {

      val messageStoreProbe = createTestProbe[EdaCommand]()
      val mailActor = spawn(TenantMailActor(tenantConfig, emailRepo))
      val replyActor = createTestProbe[MqttCommand]()

      perpareEmail("myeeg", "ZUSTIMMUNG_ECON.xml", "[EC_REQ_ONL_01.00 MessageId=123456678]")

      mailActor ! FetchEmailCommand("myeeg", "", replyActor.ref)

//      val storeMsg = messageStoreProbe.expectMessageType[MergeConversation] //(MessageStorage.FindById("RC100130202301231674475740000000030", _))
//      storeMsg.replyTo ! ConversationMerged(
//        EbMsMessage(None, "RC100001202307250000000000000000009", "sender", "receiver", ONLINE_REG_APPROVAL, messageCodeVersion=Some("01.00"), None, Some(Meter("meterid123456", None))))

      val mqttMsg = replyActor.expectMessageType[MqttPublish]
      println(mqttMsg)
      mqttMsg.mails.head.message.meter shouldBe None
      mqttMsg.mails.head.message.responseData.get.head.ResponseCode.head shouldBe 175
      mqttMsg.mails.head.message.responseData.get.head.MeteringPoint shouldBe Some("AT0030000000000000000000000959561")

//      testKit.stop(mailActor.ref)
      Mailbox.clearAll()
    }
  }

}
