package at.energydash.scenario

import at.energydash.testsupport.FakePonton.Status
import at.energydash.testsupport.{Fixtures, ProtocolCatalog, TestDb}
import io.circe.Json
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

/** S1 – S10: one EDA process each, end to end through the actor graph; the grid operator is the fake Ponton. */
class ProcessScenarioSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with ScenarioSupport {
  import at.energydash.domain.JsonImplicit._
  import io.circe.generic.auto._
  import io.circe.syntax._

  private lazy val g = graph("process")
  override def afterAll(): Unit = { g.stop(); super.afterAll() }

  private val V3 = "v3/eda-xml/in/"
  private def row(code: String, label: String) = ProtocolCatalog.outbound.find(r => r.code == code && r.label.contains(label)).get

  /** Sends the request, waits for its SOAP request and its echo; returns the conversation id eda-xp gave it. */
  private def send(code: String, label: String, topic: String): String = {
    g
    request(row(code, label).message.asJson)
    awaitPonton(1).size shouldBe 1
    val echo = await(s"eda/response/rc100401/protocol/$topic")
    echo.hcursor.downField("conversationId").as[String].toOption.get
  }

  private def answer(doc: scala.xml.Elem, code: String, topic: String, receiver: String = Tenant): Json = {
    val (sender, _) = Fixtures.routing(doc)
    postMessage(g, Fixtures.inboundEnvelope(doc, sender, receiver, code)).isSuccess shouldBe true
    await(s"eda/response/${receiver.toLowerCase}/protocol/$topic")
  }

  private def field(j: Json, path: String*): Option[Json] = path.foldLeft(Option(j))((c, p) => c.flatMap(_.hcursor.downField(p).focus))
  private def str(j: Json, path: String*): Option[String] = field(j, path: _*).flatMap(_.asString)

  "S1: ECON online registration" should {
    "send the request, store the conversation and merge the stored ecId into every answer" in {
      val conv = send("ANFORDERUNG_ECON", "02.40", "ec_req_onl")
      TestDb.count(s"SELECT count(*) FROM eda.conversation WHERE id = '$conv'") shouldBe 1
      Seq(("CMNotification-01.12-ANTWORT_ECON-99.xml", "ANTWORT_ECON"), ("CMNotification-01.12-ZUSTIMMUNG_ECON-175.xml", "ZUSTIMMUNG_ECON"),
        ("ECMPList-01.10-ABSCHLUSS_ECON-consent.xml", "ABSCHLUSS_ECON")).foreach { case (fixture, code) =>
        val j = answer(answerTo(V3 + fixture, conv), code, "ec_req_onl")
        (str(j, "messageCode"), str(j, "conversationId"), str(j, "ecId")) shouldBe ((Some(code), Some(conv), Some(Community)))
      }
    }
  }

  "S2: ECOF offline registration rejected" should {
    "publish the rejection with its response code and the stored ecId" in {
      val conv = send("ANFORDERUNG_ECOF", "02.30", "ec_req_off")
      val j = answer(answerTo(V3 + "CMNotification-01.12-ABLEHNUNG_ECON-182.xml", conv, code = Some("ABLEHNUNG_ECOF")), "ABLEHNUNG_ECOF", "ec_req_off")
      str(j, "messageCode") shouldBe Some("ABLEHNUNG_ECOF")
      str(j, "ecId") shouldBe Some(Community)
      field(j, "responseData").flatMap(_.asArray).map(_.nonEmpty) shouldBe Some(true)
    }
  }

  "S3: ECP metering-point list" should {
    "publish one meter-list entry per meter and time window (2 meters, 3 windows) with the stored ecId" in {
      val conv = send("ANFORDERUNG_ECP", "02.10", "ec_podlist")
      val j = answer(answerTo(V3 + "ECMPList-01.10-SENDEN_ECP-two-meters-two-windows.xml", conv), "SENDEN_ECP", "ec_podlist")
      val meters = field(j, "meterList").flatMap(_.asArray).get.flatMap(m => str(m, "meteringPoint"))
      (meters.size, meters.distinct.size) shouldBe ((3, 2))
      str(j, "ecId") shouldBe Some(Community)
    }
  }

  "S4: CPF factor change" should {
    "merge the stored meter list into the answer and into a rejection" in {
      val conv = send("ANFORDERUNG_CPF", "01.10", "ec_prtfact_change")
      val ok = answer(answerTo(V3 + "ECMPList-01.10-ANTWORT_CPF.xml", conv), "ANTWORT_CPF", "ec_prtfact_change")
      field(ok, "meterList").flatMap(_.asArray).map(_.flatMap(m => str(m, "meteringPoint"))) shouldBe
        Some(Vector("AT0030000000000000000000000401001", "AT0030000000000000000000000401002"))
      val no = answer(answerTo(V3 + "CPNotification-01.13-ANTWORT_PT-99.xml", conv, code = Some("ABLEHNUNG_CPF")), "ABLEHNUNG_CPF", "ec_prtfact_change")
      (str(no, "messageCode"), field(no, "meterList").flatMap(_.asArray).map(_.size)) shouldBe ((Some("ABLEHNUNG_CPF"), Some(2)))
    }
  }

  "S5: PT metering values" should {
    "merge meter and ecId into the answer and the rejection" in {
      val conv = send("ANFORDERUNG_PT", "03.00", "cr_req_pt")
      Seq("ANTWORT_PT", "ABLEHNUNG_PT").foreach { code =>
        val j = answer(answerTo(V3 + "CPNotification-01.13-ANTWORT_PT-99.xml", conv, code = Some(code)), code, "cr_req_pt")
        (str(j, "meter", "meteringPoint"), str(j, "ecId")) shouldBe ((Some("AT0030000000000000000000000401001"), Some(Community)))
      }
    }
  }

  "S6: CCMS revoke" should {
    "merge consent end and ecId into the answer" in {
      val conv = send("AUFHEBUNG_CCMS", "01.30", "cm_rev_sp")
      val j = answer(answerTo(V3 + "CMNotification-01.12-ANTWORT_CCMS-176.xml", conv), "ANTWORT_CCMS", "cm_rev_sp")
      (field(j, "consentEnd").flatMap(_.asNumber).flatMap(_.toLong), str(j, "ecId")) shouldBe ((Some(4102354800000L), Some(Community)))
    }
  }

  "S7: consumption record for a community with a base.eeg row" should {
    "publish 100 quarter hours per meter on cr_msg (Base64+gzip) with the ecId from base.eeg" in {
      g
      val doc = answerTo(V3 + "ConsumptionRecord-01.40-october-100.xml", "RC100401209901010000000000009999", dropElements = Set("ECID"))
      val j = answer(doc, "DATEN_CRMSG", "cr_msg")
      str(j, "ecId") shouldBe Some(Community)
      field(j, "energy").flatMap(_.asArray).get.head.hcursor.downField("data").focus.flatMap(_.asArray).get
        .map(d => d.hcursor.downField("value").focus.flatMap(_.asArray).get.size) should contain only 100
    }
  }

  "S8: consumption record 01.31 without ecId for a tenant without a community row" should {
    "be published through /admin/upload without ecId (pinned)" in {
      g
      val doc = answerTo(V3 + "ConsumptionRecord-01.31-summer-96-no-ecid.xml", "C8", receiver = "RC100507")
      upload(g, "CR_MSG_03.03", doc.toString).isSuccess shouldBe true
      val j = await("eda/response/rc100507/protocol/cr_msg")
      str(j, "ecId") shouldBe None
    }
  }

  "S9: request of an unknown tenant" should {
    "answer an error on the tenant's error topic and send nothing" in {
      g
      request(row("ANFORDERUNG_ECP", "02.10").message.copy(sender = "RC999999").asJson)
      val j = await("eda/response/rc999999/protocol/error")
      str(j, "errorMessage") shouldBe Some("Tenant not registered")
      awaitPonton(1, 1.second) shouldBe empty
      TestDb.count("SELECT count(*) FROM eda.conversation") shouldBe 0
    }
  }

  "S10: Ponton refuses the request" should {
    "answer an error with step Send KEP, store nothing, and process the next request" in {
      g
      ponton.script(Status(500))
      request(row("ANFORDERUNG_ECP", "02.10").message.asJson)
      val j = await("eda/response/rc100401/protocol/error")
      str(j, "reason") shouldBe Some("Send KEP")
      TestDb.count("SELECT count(*) FROM eda.conversation") shouldBe 0
      ponton.reset()
      send("ANFORDERUNG_ECP", "02.10", "ec_podlist")
      TestDb.count("SELECT count(*) FROM eda.conversation") shouldBe 1
    }
  }
}
