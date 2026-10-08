package at.energydash.scenario

import at.energydash.testsupport.ProtocolCatalog
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

/** S11: message ids across a restart — they continue with the same journal and repeat with a fresh one (#8). */
class RestartScenarioSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with ScenarioSupport {
  import at.energydash.domain.JsonImplicit._
  import io.circe.generic.auto._
  import io.circe.syntax._

  private val msg = ProtocolCatalog.outbound.find(r => r.code == "ANFORDERUNG_ECP" && r.label.contains("02.10")).get.message

  /** Runs one request on a fresh graph over `journal` and returns the sequence number of its message id. */
  private def oneRequest(name: String, journal: String): Long = {
    val g = graph(name, journal)
    try {
      ponton.reset()
      request(msg.asJson)
      awaitPonton(1).size shouldBe 1
      val id = await("eda/response/rc100401/protocol/ec_podlist").hcursor.downField("messageId").as[String].toOption.get
      id.takeRight(10).toLong
    } finally g.stop()
  }

  "S11: a restart" should {
    "continue the message ids with the same journal and repeat them with a fresh one (#8, pinned)" in {
      val journal = s"target/test-storage/s11-${System.nanoTime()}"
      val first = oneRequest("s11a", journal)
      val second = oneRequest("s11b", journal)
      second should be > first
      // The production journal path is relative (#8): a container without it on the volume starts fresh,
      // and the ids repeat — what this pins.
      oneRequest("s11c", s"target/test-storage/s11-fresh-${System.nanoTime()}") shouldBe first
    }
  }
}
