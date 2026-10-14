package at.energydash.scenario

import at.energydash.testsupport.KnownError.knownError
import at.energydash.testsupport._
import io.circe.parser.parse
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.LocalDate
import scala.concurrent.duration._

/**
 * S0 — every row of the protocol catalog end to end through the actor graph (protocol-changes.md §5 step 6):
 * outbound rows as `eda/request` to the fake Ponton, inbound rows over HTTP to MQTT. No scenario is written per
 * protocol version — a new catalog row is tested here too.
 */
class CatalogScenarioSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with ScenarioSupport {
  import at.energydash.domain.JsonImplicit._
  import io.circe.generic.auto._
  import io.circe.syntax._

  private lazy val g = graph("catalog")
  override def afterAll(): Unit = { g.stop(); super.afterAll() }

  private val process = ProtocolCatalog.codes.map(c => c.code -> c.process).toMap
  private val IdFormat = """RC100401\d{8}\d+\d{10}"""

  "S0: outbound" should {
    ProtocolCatalog.outbound.foreach { row =>
      s"S0: ${row.name}" in {
        g
        def run(): Unit = {
          val before = Normalise.dateTokens(LocalDate.now(Normalise.Vienna))
          request(row.message.asJson)
          val sent = awaitPonton(1)
          val after = Normalise.dateTokens(LocalDate.now(Normalise.Vienna))
          assert(sent.size == 1, "one SOAP request at the fake Ponton")
          val env = sent.head.envelope
          val header = SoapEnvelope.header(env)
          header("MessageId") should fullyMatch regex """MSG\d{10}@RC100401"""
          val echo = await(s"eda/response/rc100401/protocol/${process(row.code).toLowerCase}")
          echo.hcursor.downField("messageId").as[String].toOption.get should fullyMatch regex IdFormat
          if (row.knownError.isEmpty) {
            Golden.check(row.goldenBase + ".header", Seq(Normalise.header(header)))
            Golden.check(row.goldenBase + ".xml", Seq(before, after).distinct.map(d => Normalise.document(SoapEnvelope.document(env), d)))
          } else {
            row.headerVersion.foreach(v => header("MessageVersion") shouldBe v)
            row.xsd.foreach(x => Xsd.validate(SoapEnvelope.document(env), x))
          }
        }
        row.knownError match {
          case None => run()
          case Some(id) => knownError(id) {
            if (row.expectFailure) { request(row.message.asJson); awaitPonton(1, 2.seconds) shouldBe empty }
            else run()
          }
        }
      }
    }
  }

  "S0: inbound" should {
    ProtocolCatalog.inbound.filter(r => r.via == "ponton" || r.via == "upload").foreach { row =>
      s"S0: ${row.name}" in {
        g
        val doc = Fixtures.ebUtilitiesDocument(Fixtures.xml(row.fixture)).get
        val (sender, receiver) = Fixtures.routing(doc)
        val status = row.via match {
          case "ponton" => postMessage(g, Fixtures.inboundEnvelope(doc, sender, receiver, row.code))
          case _ => upload(g, s"${process(row.code)}_01.00", doc.toString)
        }
        status.isSuccess shouldBe true
        val published = await(s"eda/response/${receiver.toLowerCase}/protocol/${process(row.code).toLowerCase}")
        published shouldBe parse(Fixtures.text(s"protocol/golden/${row.golden}")).toOption.get
      }
    }
  }
}
