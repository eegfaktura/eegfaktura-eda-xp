package at.energydash.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import at.energydash.actors.MqttPublisher.MqttCommand
import at.energydash.domain.EbMsMessage
import at.energydash.domain.dao.{Db, SlickEmailOutboxRepository, TenantConfig}
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.{EmailMock, EmbeddedDb}
import org.scalatest.BeforeAndAfter
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.DurationInt

class TenantMailWorkerSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with BeforeAndAfter with EmbeddedDb with EmailMock {
  import scala.concurrent.ExecutionContext.Implicits.global

  val dbConfig = Db.getConfig
  val mailRepo = new SlickEmailOutboxRepository(dbConfig)
  val tenantConfig = TenantConfig(tenant = "TE100100", cType = "KEP", Some("email.com"),  None, None, None, None, None, None, None, None, active = true)

  "Tenant Mail Actor" should {
    "Handle EDA Mail Message" in {
      val mqttPublisherProbe = createTestProbe[MqttCommand]()
      val edaResponse = createTestProbe[EdaCommand]

      val tenantWorker = spawn(FetchMailTenantWorker(tenantConfig, mqttPublisherProbe.ref, mailRepo), s"worker-${tenantConfig.tenant}")

      val testMessage = EbMsMessage(messageId = Some("1234"), conversationId = "con",
        sender = "myeeg",
        receiver = "rec",
        messageCode = EbMsMessageType.EDA_MSG_AUFHEBUNG_CCMS,
        messageCodeVersion = Some("02.00"),
        requestId = Some("567890"))

      tenantWorker ! SendEdaCommand(testMessage, edaResponse.ref)

      edaResponse.receiveMessage(5.seconds)
    }
  }

}
