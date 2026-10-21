package at.energydash.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import at.energydash.actors.MqttPublisher.MqttCommand
import at.energydash.actors.TenantProvider.TenantStart
import at.energydash.config.Config
import at.energydash.domain.EbMsMessage
import at.energydash.domain.dao.TenantConfig
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.{EmailMock, EmbeddedDb}
import com.typesafe.slick.testkit.util.ProfileTest
import com.typesafe.slick.testkit.util.StandardTestDBs.Postgres
import org.jvnet.mock_javamail.{Mailbox, MockTransport}
import org.scalatest.BeforeAndAfter
import org.scalatest.wordspec.AnyWordSpecLike

import javax.mail.Provider
import javax.mail.internet.MimeMultipart

class MyPostgresTest extends ProfileTest(Postgres)

class MockedSMTPProvider
  extends Provider(Provider.Type.TRANSPORT, "mocked", classOf[MockTransport].getName, "Mock", null)

class TenantProviderSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with BeforeAndAfter with EmbeddedDb with EmailMock {
  import scala.concurrent.ExecutionContext.Implicits.global

  private val testEdaMessage = EbMsMessage(messageId = Some("1234"), conversationId = "con",
    sender = "sender",
    receiver = "rec",
    messageCode = EbMsMessageType.OFFLINE_REG_INIT,
    messageCodeVersion = Some("02.00"),
    requestId = Some("567890"))

  "Tenant Provider Actor" should {
    "Handle Eda Message OFFLINE Reg" in {
      val mqttPublisherProbe = createTestProbe[MqttCommand]()
      val edaResponse = createTestProbe[EdaCommand]
      val tenantActor = spawn(TenantProvider(mqttPublisherProbe.ref))
      val testMessage = EbMsMessage(messageId = Some("1234"), conversationId = "con",
        sender = "myeeg",
        receiver = "rec",
        messageCode = EbMsMessageType.OFFLINE_REG_INIT,
        messageCodeVersion = Some("02.00"),
        requestId = Some("567890"))
      tenantActor ! TenantStart
      // sent while the tenants are still loading: queued and processed after initialization
      tenantActor ! PassEdaCommand("myeeg", testMessage, edaResponse.ref)
      edaResponse.expectMessage[SendEdaResponse](SendEdaResponse(testMessage))
      // the test config (epmsmail.mail.test.enable) sends every mail to the test receiver
      val msg = Mailbox.get(s"${Config.testReceiver}@email.com").get(0)
      msg.getSubject shouldBe "[EC_REQ_OFF_02.00 MessageId=1234]"
      msg.getContent shouldBe a[MimeMultipart]
    }

    "Handle Eda Message with unregistered participant" in {
      val mqttPublisherProbe = createTestProbe[MqttCommand]()
      val edaResponse = createTestProbe[EdaCommand]
      val tenantActor = spawn(TenantProvider(mqttPublisherProbe.ref))
      val testMessage = EbMsMessage(messageId = Some("1234"), conversationId = "con",
        sender = "sender",
        receiver = "rec",
        messageCode = EbMsMessageType.OFFLINE_REG_INIT,
        messageCodeVersion = Some("02.00"),
        requestId = Some("567890"))
      tenantActor ! TenantStart

      tenantActor ! PassEdaCommand("sender", testMessage, edaResponse.ref)
      val errorResponse = edaResponse.expectMessage[SendResponseError](SendResponseError("sender", "rec", "Tenant not registered"))
      println(errorResponse)
    }

    "Handle reconfigure Tenant Settings" in {
      val mqttPublisherProbe = createTestProbe[MqttCommand]()
      val configResponse = createTestProbe[EdaCommand]
      val tenantActor = spawn(TenantProvider(mqttPublisherProbe.ref))
      val testMessage = TenantConfig(
        tenant = "mymaileeg", cType = "KEP", active = true, domain = None, host = None, imapPort = None, smtpHost = None, smtpPort = None, user = None, passwd = None, imapSecurity = None, smtpSecurity = None)
      tenantActor ! TenantStart
      // TenantModified is not queued during initialization: wait until the provider answers
      val probe = createTestProbe[EdaCommand]()
      tenantActor ! PassEdaCommand("unknown", testEdaMessage, probe.ref)
      probe.expectMessageType[SendResponseError]

      tenantActor ! TenantModified(testMessage, configResponse.ref)
      configResponse.expectMessageType[at.energydash.actors.ResponseOk]
    }

    "Process messages queued during initialization in arrival order" in {
      val mqttPublisherProbe = createTestProbe[MqttCommand]()
      val edaResponse = createTestProbe[EdaCommand]()
      val tenantActor = spawn(TenantProvider(mqttPublisherProbe.ref))
      tenantActor ! TenantStart

      tenantActor ! PassEdaCommand("first", testEdaMessage, edaResponse.ref)
      tenantActor ! PassEdaCommand("second", testEdaMessage, edaResponse.ref)
      tenantActor ! PassEdaCommand("third", testEdaMessage, edaResponse.ref)

      edaResponse.expectMessage(SendResponseError("first", "rec", "Tenant not registered"))
      edaResponse.expectMessage(SendResponseError("second", "rec", "Tenant not registered"))
      edaResponse.expectMessage(SendResponseError("third", "rec", "Tenant not registered"))
    }
  }
}
