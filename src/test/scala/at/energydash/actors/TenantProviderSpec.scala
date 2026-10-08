package at.energydash.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import at.energydash.actors.MqttPublisher.{MqttCommand, MqttPublishCommand}
import at.energydash.actors.TenantProvider.TenantStart
import at.energydash.domain.EbMsMessage
import at.energydash.domain.dao.TenantConfig
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.testsupport.KnownError.knownError
import at.energydash.testsupport.TestDb
import at.energydash.{EmailMock, EmbeddedDb}
import org.jvnet.mock_javamail.{Mailbox, MockTransport}
import org.scalatest.wordspec.AnyWordSpecLike

import javax.mail.Provider
import scala.concurrent.duration.DurationInt

class MockedSMTPProvider
  extends Provider(Provider.Type.TRANSPORT, "mocked", classOf[MockTransport].getName, "Mock", null)

class TenantProviderSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with EmbeddedDb with EmailMock {

  // MAIL tenants on the mock-javamail domain (formerly seeded by the test-only migration V000.002).
  override def beforeAll(): Unit = {
    super.beforeAll()
    TestDb.reset()
    TestDb.seedTenant("myeeg", "MAIL")
    TestDb.seedTenant("mymaileeg", "MAIL")
  }

  private def offlineReg(sender: String, receiver: String) = EbMsMessage(messageId = Some("1234"), conversationId = "con",
    sender = sender, receiver = receiver, messageCode = EbMsMessageType.OFFLINE_REG_INIT,
    messageCodeVersion = Some("02.00"), requestId = Some("567890"))

  /** Waits until the provider has left its initialisation: a command for an unknown tenant is answered. */
  private def awaitReady(provider: ActorRef[EdaCommand]): Unit = {
    val probe = createTestProbe[EdaCommand]()
    val ready = Iterator.continually {
      provider ! PassEdaCommand("not-a-tenant", offlineReg("not-a-tenant", "rec"), probe.ref)
      scala.util.Try(probe.expectMessageType[SendResponseError](200.millis)).isSuccess
    }.take(50).contains(true)
    assert(ready, "tenant provider did not finish its initialisation within 10 s")
  }

  "Tenant Provider Actor" should {
    // 261005-ca4: a PassEdaCommand sent during the initialisation is queued and then dropped.
    "Handle Eda Message OFFLINE Reg [261005-ca4]" in knownError("261005-ca4") {
      val edaResponse = createTestProbe[EdaCommand]()
      val tenantActor = spawn(TenantProvider(createTestProbe[MqttCommand]().ref))
      val testMessage = offlineReg("myeeg", "rec")
      tenantActor ! TenantStart
      tenantActor ! PassEdaCommand("myeeg", testMessage, edaResponse.ref)
      edaResponse.expectMessage(3.seconds, SendEdaResponse(testMessage))
      Mailbox.get("rec@email.com").get(0).getSubject shouldBe "[EC_REQ_OFF_02.00 MessageId=1234]"
    }

    "Handle Eda Message with unregistered participant [261005-ca4]" in knownError("261005-ca4") {
      val edaResponse = createTestProbe[EdaCommand]()
      val tenantActor = spawn(TenantProvider(createTestProbe[MqttCommand]().ref))
      tenantActor ! TenantStart
      tenantActor ! PassEdaCommand("sender", offlineReg("sender", "rec"), edaResponse.ref)
      edaResponse.expectMessage(3.seconds, SendResponseError("sender", "rec", "Tenant not registered"))
    }

    "Handle malformed Eda Message [261005-ca4]" in knownError("261005-ca4") {
      val edaResponse = createTestProbe[EdaCommand]()
      val tenantActor = spawn(TenantProvider(createTestProbe[MqttCommand]().ref))
      tenantActor ! TenantStart
      tenantActor ! PassEdaCommand("myeeg", offlineReg("myeeg", "netz ooe"), edaResponse.ref)
      edaResponse.expectMessage(3.seconds,
        SendResponseError("myeeg", "NETZ OOE", "Local address contains control or whitespace", "Send Mail"))
    }

    "Handle reconfigure Tenant Settings" in {
      val mqttPublisherProbe = createTestProbe[MqttCommand]()
      val configResponse = createTestProbe[EdaCommand]()
      val tenantActor = spawn(TenantProvider(mqttPublisherProbe.ref))
      tenantActor ! TenantStart
      awaitReady(tenantActor)

      tenantActor ! TenantModified(TenantConfig(tenant = "mymaileeg", cType = "KEP", active = true, domain = None,
        host = None, imapPort = None, smtpHost = None, smtpPort = None, user = None, passwd = None,
        imapSecurity = None, smtpSecurity = None), configResponse.ref)
      configResponse.expectMessageType[ResponseOk]
      val online = mqttPublisherProbe.expectMessageType[MqttPublishCommand]
      online.command.tenant shouldBe "mymaileeg"
      online.command.command shouldBe "pontonOnlineState"
    }
  }
}
