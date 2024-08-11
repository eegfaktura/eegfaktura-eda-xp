package at.energydash.actors

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import at.energydash.actors.MessageStorage.MessageFound
import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.EbMsMessageType
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.File
import scala.reflect.io.Directory

class MessageStorageSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with BeforeAndAfterAll {
  "The Message Storage" should {
    "Add messages" in {
      val probe = createTestProbe[MessageStorage.AddMessageResult]()
      val storage = spawn(MessageStorage())
      val message = EbMsMessage(
        Some("AT101212120012121212"),
        "AT3241234124312432143",
        "AT100130",
        "RC003000",
        EbMsMessageType.ENERGY_FILE_RESPONSE, messageCodeVersion=Some("01.00"))

      storage ! MessageStorage.AddMessage(message, probe.ref)
      probe.expectMessageType[MessageStorage.Added]
    }

    "Find messages by id" in {
      val probe = createTestProbe[MessageStorage.AddMessageResult]()
      val storage = spawn(MessageStorage())
      val message = EbMsMessage(
        Some("AT9999999999999999990"),
        "AT3241234124312432143",
        "AT100130",
        "RC003000",
        EbMsMessageType.ENERGY_FILE_RESPONSE, messageCodeVersion=Some("01.00"))

      storage ! MessageStorage.AddMessage(message, probe.ref)
      val added = probe.expectMessageType[MessageStorage.Added]

      val lookupProbe = createTestProbe[MessageStorage.FindMessageResult]()
      storage ! MessageStorage.FindById(added.msg.conversationId, lookupProbe.ref)
      val found = lookupProbe.expectMessageType[MessageFound]
      found.conversation.conversationId shouldBe "AT3241234124312432143"
      found.conversation.message shouldBe defined
    }

    "Generate MessageID and RequestID" in {
      val probe = createTestProbe[PrepareMessageActor.PrepareMessageResult]()
      val storage = spawn(PrepareMessageActor())
      val message = EbMsMessage(
        None,
        "AT3241234124312432143",
        "AT100130",
        "RC003000",
        EbMsMessageType.ENERGY_FILE_RESPONSE,
        messageCodeVersion=Some("01.00"),
      )

      storage ! PrepareMessageActor.PrepareMessage(message, probe.ref)
      val prepared = probe.expectMessageType[PrepareMessageActor.Prepared]
      prepared.message.messageId.get should endWith("0000000001")

      storage ! PrepareMessageActor.PrepareMessage(message, probe.ref)
      val prepared1 = probe.expectMessageType[PrepareMessageActor.Prepared]
      prepared1.message.messageId.get should endWith("0000000002")
    }
  }

  override protected def afterAll(): Unit = {
    val dir = new Directory(new File(system.settings.config.getString("akka.persistence.journal.leveldb.dir")))
    println(dir)
    dir.deleteRecursively()
  }
}
