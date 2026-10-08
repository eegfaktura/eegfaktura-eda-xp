package at.energydash.interfaces

import at.energydash.EmbeddedDb
import at.energydash.actors.ConversationEntity.{InitConversation, InitDone, MergeNotification, NotificationMerged}
import at.energydash.actors.MqttPublisher.{AggregateNotification, EdaNotification, MqttCommand, MqttPublish}
import at.energydash.actors.{ConversationEntity, EbMsAggregator, EdaCommand}
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.domain.{EbMsMessage, Meter}
import at.energydash.testsupport.TestDb
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

/** The conversation store (requests kept for the answers) and the aggregator that enriches answers. */
class ConversationStoreSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with EmbeddedDb with BeforeAndAfterEach {
  override def beforeEach(): Unit = TestDb.reset()

  private val request = EbMsMessage(messageId = Some("M1"), conversationId = "RC100401209901010000000000000002",
    sender = "RC100401", receiver = "AT003000", messageCode = EbMsMessageType.ENERGY_SYNC_REQ,
    meter = Some(Meter("AT0030000000000000000000000401001", None)), ecId = Some("EC-REQUEST"))
  private val answer = EbMsMessage(conversationId = request.conversationId, sender = "AT003000", receiver = "RC100401",
    messageCode = EbMsMessageType.ENERGY_SYNC_RES)

  "ConversationEntity" should {
    "store a request and merge it into the answer" in {
      val entity = spawn(ConversationEntity())
      val probe = createTestProbe[EdaCommand]()
      entity ! InitConversation(request, probe.ref)
      probe.expectMessage(InitDone(request))
      TestDb.count(s"SELECT count(*) FROM eda.conversation WHERE id = '${request.conversationId}'") shouldBe 1
      entity ! MergeNotification(EdaNotification("CR_REQ_PT", answer), probe.ref)
      val merged = probe.expectMessageType[NotificationMerged].notification.message
      (merged.meter, merged.ecId) shouldBe ((request.meter, Some("EC-REQUEST")))
    }
  }

  "EbMsAggregator" should {
    val crmsg = EbMsMessage(conversationId = "unknown", sender = "AT003000", receiver = "RC100401",
      messageCode = EbMsMessageType.ENERGY_FILE_RESPONSE)
    def aggregate(n: EdaNotification): EdaNotification = {
      val aggregator = spawn(EbMsAggregator(spawn(ConversationEntity())))
      val probe = createTestProbe[MqttCommand]()
      aggregator ! AggregateNotification("", List(n), probe.ref)
      probe.expectMessageType[MqttPublish].mails.head
    }
    "fill a missing ecId from base.eeg for the receiver" in {
      TestDb.seedEeg("RC100401", "AT00300000000RC100401000000000001")
      aggregate(EdaNotification("CR_MSG", crmsg)).message.ecId shouldBe Some("AT00300000000RC100401000000000001")
    }
    "leave the ecId empty when the receiver has no community row (pinned)" in {
      aggregate(EdaNotification("CR_MSG", crmsg)).message.ecId shouldBe None
    }
    "not touch message types that need no ecId" in {
      TestDb.seedEeg("RC100401", "AT00300000000RC100401000000000001")
      aggregate(EdaNotification("CM_REV_IMP", crmsg.copy(messageCode = EbMsMessageType.EDA_MSG_AUFHEBUNG_CCMI))).message.ecId shouldBe None
    }
  }
}
