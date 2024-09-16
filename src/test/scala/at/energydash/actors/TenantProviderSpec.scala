package at.energydash.actors

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import at.energydash.actors.MqttPublisher.MqttCommand
import at.energydash.actors.TenantProvider.TenantStart
import at.energydash.domain.EbMsMessage
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

  "Tenant Provider Actor" should {
    "Handle Eda Message OFFLINE Reg" in {
      val mailActorProbe = createTestProbe[MqttCommand]()
      val edaResponse = createTestProbe[EdaCommand]
      val tenantActor = spawn(TenantProvider(mailActorProbe.ref))
      val testMessage = EbMsMessage(messageId = Some("1234"), conversationId = "con",
        sender = "myeeg",
        receiver = "rec",
        messageCode = EbMsMessageType.OFFLINE_REG_INIT,
        messageCodeVersion = Some("02.00"),
        requestId = Some("567890"))
      tenantActor ! TenantStart
      //      Thread.sleep(1000)

      tenantActor ! PassEdaCommand("myeeg", testMessage, edaResponse.ref)
      //      println(edaResponse.receiveMessages(1))
      edaResponse.expectMessage[SendEdaResponse](SendEdaResponse(testMessage))
      val msg = Mailbox.get("rec@email.com").get(0)
      msg.getSubject shouldBe "[EC_REQ_OFF_02.00 MessageId=1234]"
      val content = msg.getContent.asInstanceOf[MimeMultipart]
      println(scala.io.Source.fromInputStream(content.getBodyPart(0).getInputStream).mkString)
    }

    "Handle Eda Message with unregistered participant" in {
      val mailActorProbe = createTestProbe[MqttCommand]()
      val edaResponse = createTestProbe[EdaCommand]
      val tenantActor = spawn(TenantProvider(mailActorProbe.ref))
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

    "Handle malformed Eda Message" in {
      val mailActorProbe = createTestProbe[MqttCommand]()
      val edaResponse = createTestProbe[EdaCommand]
      val tenantActor = spawn(TenantProvider(mailActorProbe.ref))
      val testMessage = EbMsMessage(messageId = Some("1234"), conversationId = "con",
        sender = "myeeg",
        receiver = "netz ooe",
        messageCode = EbMsMessageType.OFFLINE_REG_INIT,
        messageCodeVersion = Some("02.00"),
        requestId = Some("567890"))
      tenantActor ! TenantStart

      tenantActor ! PassEdaCommand("myeeg", testMessage, edaResponse.ref)
      val errorResponse = edaResponse.expectMessage[SendResponseError](SendResponseError("myeeg", "Local address contains control or whitespace", "Send Mail"))
      println(errorResponse)
    }
  }
}
