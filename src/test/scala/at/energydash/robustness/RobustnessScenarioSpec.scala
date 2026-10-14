package at.energydash.robustness

import at.energydash.mqtt.MqttProtocol.{EdaEventReceived, EdaInboundMessage}
import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.scenario.{ScenarioGraph, ScenarioSupport}
import at.energydash.testsupport.FakePonton.{Delay, Ok}
import at.energydash.testsupport.KnownError.knownError
import at.energydash.testsupport.{ProtocolCatalog, TestBroker, TestDb}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.Tag
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._
import scala.util.Try

object Slow extends Tag("at.energydash.Slow")

/** M5: failure modes through the actor graph (rows R5, R8, R12 of m05-robustness.md; R3 in RobustnessBrokerSpec). */
class RobustnessScenarioSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with ScenarioSupport with Eventually {
  import at.energydash.domain.JsonImplicit._
  import io.circe.generic.auto._
  import io.circe.syntax._

  private val ecp = ProtocolCatalog.outbound.find(r => r.code == "ANFORDERUNG_ECP" && r.label.contains("02.10")).get.message

  "R5: Ponton is slow (35 s) but accepts" should {
    "not answer the user with an error and still store the conversation [261005-ca6]" taggedAs Slow in knownError("261005-ca6") {
      val g = graph("r5")
      try {
        ponton.script(Delay(35.seconds, Ok))
        request(ecp.asJson)
        Try(await("eda/response/rc100401/protocol/error", 40.seconds)).isFailure shouldBe true
        TestDb.count("SELECT count(*) FROM eda.conversation") shouldBe 1
      } finally g.stop()
    }
  }

  "R8: 50 requests from two tenants at once" should {
    "get 50 distinct ids, respect the throttle (5/s) and keep the order per tenant (pinned)" taggedAs Slow in {
      val g = graph("r8")
      try {
        val start = System.nanoTime()
        val sent = (1 to 50).map(i => if (i % 2 == 0) "RC100401" else "RC100507")
        sent.zipWithIndex.foreach { case (t, i) => request(ecp.copy(sender = t, conversationId = s"in-$i").asJson) }
        val echoes = (1 to 50).map(_ => mqtt.next(30.seconds).getOrElse(fail("fewer than 50 echoes"))).filter(_.topic.endsWith("/ec_podlist"))
        val elapsed = (System.nanoTime() - start).nanos
        echoes.size shouldBe 50
        val jsons = echoes.map(decode)
        val ids = jsons.map(_.hcursor.downField("messageId").as[String].toOption.get)
        ids.distinct.size shouldBe 50
        jsons.map(_.hcursor.downField("requestId").as[String].toOption.get).distinct.size shouldBe 50
        elapsed should be >= 9.seconds
        Seq("rc100401", "rc100507").foreach { t =>
          val seqs = echoes.zip(ids).filter(_._1.topic.contains(t)).map(_._2.takeRight(10).toLong)
          seqs shouldBe seqs.sorted
        }
        awaitPonton(50, 30.seconds).size shouldBe 50
      } finally g.stop()
    }
  }

  "R12: the persistence journal cannot be opened" should {
    "answer requests with a clear JSON error at once [261008-ca18]" in knownError("261008-ca18") {
      val dir = java.nio.file.Files.createDirectories(java.nio.file.Paths.get(s"target/test-storage/r12-${System.nanoTime()}"))
      java.nio.file.Files.createFile(dir.resolve("journal")) // a file where the journal directory must be
      val g = graph("r12", dir.toString)
      try {
        request(ecp.asJson)
        val j = await("eda/response/error", 5.seconds)
        j.isObject shouldBe true
      } finally g.stop()
    }
  }
}
