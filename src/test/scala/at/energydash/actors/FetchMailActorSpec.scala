package at.energydash.actors

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import at.energydash.actors.MqttPublisher.{MqttCommand, MqttPublish}
import at.energydash.actors.TenantMailActor.FetchEmailCommand
import at.energydash.domain.dao.{Db, SlickEmailOutboxRepository, TenantConfig}
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.mailer.ConfiguredMailer
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

class FetchMailActorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

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

//    "fetch many emails" in {
//
////      val messageStoreProbe = createTestProbe[MessageStorage.Command[_]]()
//
//      val mailActor = spawn(TenantMailActor(tenantConfig, messageStore, emailRepo))
//      val replyActor = createTestProbe[MqttCommand]()
//
//      (1 to 1000).foreach(_ => perpareEmail("myeeg", "ZUSTIMMUNG_ECON.xml", "[EC_REQ_ONL_01.00 MessageId=123456678]"))
//
//      mailActor ! FetchEmailCommand("myeeg", "", replyActor.ref)
//
//      val mqttMsg = replyActor.receiveMessage(5.minutes)
//
////      mqttMsg match {
////        case MqttPublish(mails) =>
////          mails.length shouldBe 1000
////        case MqttPublishError(tenant, message) =>
////          assert(false)
////      }
//
//      (for {
//        e <- emailRepo.byTenant("myeeg")
//      } yield(e)).onComplete {
//        case Success(e) => println(e)
//      }
//
//
////      emailRepo.all().onComplete {
////        case Success(l) => l.length shouldBe 1000
////        case Failure(_) => assert(false)
////      }
//
////      Thread.sleep(2000)
////      testKit.stop(mailActor.ref)
//      Mailbox.clearAll()
//    }
//
//    "fetch email collection with unknown protocol" in {
//
//      //      val messageStoreProbe = createTestProbe[MessageStorage.Command[_]]()
//      val mailActor = spawn(TenantMailActor(tenantConfig, messageStore, emailRepo))
//      val replyActor = createTestProbe[MqttCommand]()
//
//      perpareEmail("myeeg", "ZUSTIMMUNG_ECON.xml", "[EC_REQ_ONL_01.00 MessageId=123456678]")
//      perpareEmail("myeeg", "ZUSTIMMUNG_ECON.xml", "[EC_TES_ONL_01.00 MessageId=123456678]")
//      perpareEmail("myeeg", "ZUSTIMMUNG_ECON.xml", "[EC_REQ_ONL_01.00 MessageId=123456678]")
//
//      mailActor ! FetchEmailCommand("myeeg", "", replyActor.ref)
//
//      val mqttMsg = replyActor.receiveMessage(5.minutes)
//
//      mqttMsg match {
//        case MqttPublish(mails) =>
//          mails.length shouldBe 2
//        case MqttPublishError(tenant, message) =>
//          assert(false)
//      }
//      Mailbox.clearAll()
//    }
//
//    "fetch email collection with wrong header" in {
//
//      //      val messageStoreProbe = createTestProbe[MessageStorage.Command[_]]()
//      val mailActor = spawn(TenantMailActor(tenantConfig, messageStore, emailRepo))
//      val replyActor = createTestProbe[MqttCommand]()
//
//      perpareEmail("myeeg", "ZUSTIMMUNG_ECON.xml", "[EC_REQ_ONL_01.00 MessageId=123456678]")
//      perpareEmail("myeeg", "ZUSTIMMUNG_ECON.xml", "Wrong Header")
//      perpareEmail("myeeg", "ZUSTIMMUNG_ECON.xml", "[EC_REQ_ONL_01.00 MessageId=123456678]")
//
//      mailActor ! FetchEmailCommand("myeeg", "", replyActor.ref)
//
//      val mqttMsg = replyActor.receiveMessage(5.minutes)
//
//      mqttMsg match {
//        case MqttPublish(mails) =>
//          mails.length shouldBe 3
//          mails(1).message.message.messageCode shouldBe ERROR_MESSAGE
//          println(mails(1))
//        case MqttPublishError(tenant, message) =>
//          assert(false)
//      }
//      Mailbox.clearAll()
//    }
  }

}
