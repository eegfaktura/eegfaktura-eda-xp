package at.energydash.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import at.energydash.actors.PrepareMessageActor.{PrepareMessage, PrepareMessageResult, Prepared}
import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.EbMsMessageType
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files

class PrepareMessageActorSpec extends AnyWordSpec with Matchers {

  private val message = EbMsMessage(None, "", "CC100153", "AT002000",
    EbMsMessageType.EDA_MSG_AUFHEBUNG_CCMS, messageCodeVersion = Some("01.00"))

  // own actor system per case: in-memory journal, so every case starts with an empty counter
  private def withTestKit(body: ActorTestKit => Unit): Unit = {
    val testKit = ActorTestKit(ConfigFactory.parseString(
      s"""pekko.persistence.journal.plugin = "pekko.persistence.journal.inmem"
         |pekko.persistence.snapshot-store.plugin = "pekko.persistence.snapshot-store.local"
         |pekko.persistence.snapshot-store.local.dir = "${Files.createTempDirectory("snapshots").toString.replace('\\', '/')}"
         |""".stripMargin).withFallback(ConfigFactory.load()))
    try body(testKit) finally testKit.shutdownTestKit()
  }

  private def prepare(testKit: ActorTestKit, actor: org.apache.pekko.actor.typed.ActorRef[PrepareMessageActor.Command[_]]): EbMsMessage = {
    val probe = testKit.createTestProbe[PrepareMessageResult]()
    actor ! PrepareMessage(message, probe.ref)
    probe.expectMessageType[Prepared].message
  }

  "PrepareMessageActor" should {
    "count from 0 without a floor (prod)" in withTestKit { testKit =>
      val actor = testKit.spawn(PrepareMessageActor())
      prepare(testKit, actor).seqNr shouldBe Some(0)
      val second = prepare(testKit, actor)
      second.seqNr shouldBe Some(1)
      second.messageId.get should endWith("0000000002")
    }

    "continue from the floor" in withTestKit { testKit =>
      val actor = testKit.spawn(PrepareMessageActor(5000000000L))
      val first = prepare(testKit, actor)
      first.seqNr shouldBe Some(5000000000L)
      PontonService.buildMessageId(first.sender, first.seqNr.get) shouldBe "MSG5000000000@CC100153"
      first.messageId.get should endWith("5000000001")
      first.conversationId should endWith("5000000002")
      prepare(testKit, actor).seqNr shouldBe Some(5000000001L)
    }

    "keep a counter above the floor, also after a restart" in withTestKit { testKit =>
      val actor = testKit.spawn(PrepareMessageActor(10))
      prepare(testKit, actor).seqNr shouldBe Some(10)
      prepare(testKit, actor).seqNr shouldBe Some(11)
      testKit.stop(actor)

      // a lower floor never sets the counter back
      val restarted = testKit.spawn(PrepareMessageActor(5))
      prepare(testKit, restarted).seqNr shouldBe Some(12)
      testKit.stop(restarted)

      // a higher floor than the counter lifts it
      val raised = testKit.spawn(PrepareMessageActor(100))
      prepare(testKit, raised).seqNr shouldBe Some(100)
    }
  }

  "seqFloor" should {
    def floor(v: String) = PrepareMessageActor.seqFloor(ConfigFactory.parseString(s"app.message-seq-floor = $v"))

    "be 0 by default" in {
      PrepareMessageActor.seqFloor(ConfigFactory.load()) shouldBe 0
    }

    "accept 0 to 9000000000" in {
      floor("5000000000") shouldBe 5000000000L
      floor("9000000000") shouldBe 9000000000L
    }

    "reject values outside, so ids keep 10 digits" in {
      an[IllegalArgumentException] should be thrownBy floor("-1")
      an[IllegalArgumentException] should be thrownBy floor("9000000001")
    }
  }
}
