package at.energydash.contract

import at.energydash.domain.EbMsMessage
import at.energydash.domain.eda.MessageHelper
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.mqtt.path.MqttPaths
import at.energydash.testsupport.{Fixtures, LogCapture, ProtocolCatalog}
import io.circe.Json
import io.circe.parser.{decode, parse}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Try

/** Contracts with eegfaktura-backend (copies in src/test/resources/contract/backend, README there). */
class BackendContractSpec extends AnyWordSpec with Matchers {
  import at.energydash.domain.JsonImplicit._
  import io.circe.generic.auto._

  private val goSource = Fixtures.text("contract/backend/mqtt.go.txt")
  /** All json tags of the backend's MQTT structs (Go's decoder matches them case-insensitively). */
  private val backendTags: Set[String] = """json:"([A-Za-z]+)""".r.findAllMatchIn(goSource).map(_.group(1).toLowerCase).toSet
  private def struct(name: String): Seq[String] = {
    val body = goSource.split(s"type $name struct \\{", 2)(1).takeWhile(_ != '}')
    """json:"([A-Za-z]+)""".r.findAllMatchIn(body).map(_.group(1)).toSeq
  }
  private val backendCodes = """EbMsMessageType = "([A-Z_]+)"""".r.findAllMatchIn(goSource).map(_.group(1)).toSet

  private def keys(j: Json, prefix: String = ""): Set[String] = j.fold(Set.empty, _ => Set.empty, _ => Set.empty, _ => Set.empty,
    arr => arr.flatMap(keys(_, prefix)).toSet,
    obj => obj.toMap.flatMap { case (k, v) => keys(v, k) + k }.toSet)

  "C1 eda/request (backend → eda-xp)" should {
    "know every message code the backend defines (ERROR_PROCESS: a backend constant without use, pinned)" in {
      backendCodes.diff(EbMsMessageType.values.map(_.toString)) shouldBe Set("ERROR_PROCESS")
    }
    "read every field of the backend's EbmsMessage" in {
      val fields = classOf[EbMsMessage].getDeclaredFields.map(_.getName.toLowerCase).toSet
      struct("EbmsMessage").map(_.toLowerCase).filterNot(fields) shouldBe empty
    }
    "decode a request as the backend writes it (all fields, integer factor, epoch ms)" in {
      val request = """{"conversationId":"C","messageId":"M","sender":"RC100401","receiver":"AT003000",
        |"messageCode":"ANFORDERUNG_CPF","messageCodeVersion":"01.10","requestId":"R",
        |"meter":{"meteringPoint":"AT0030000000000000000000000401001","direction":"CONSUMPTION","activation":4070905200000,
        |"partFact":50,"from":4070905200000,"to":4102354800000,"plantCategory":"PV","share":12.5,"consentId":"K"},
        |"ecId":"EC","ecType":"LOCAL","ecDisModel":"DYNAMIC","timeline":{"from":4070905200000,"to":4102354800000},
        |"meterList":[{"meteringPoint":"AT0030000000000000000000000401002","partFact":75}],"consentEnd":4102354800000,"reason":"x"}""".stripMargin
      val m = decode[EbMsMessage](request).fold(e => fail(e.toString), identity)
      (m.meter.flatMap(_.partFact), m.meterList.map(_.size), m.ecId, m.consentEnd.map(_.getTime)) shouldBe
        ((Some(BigDecimal(50)), Some(1), Some("EC"), Some(4102354800000L)))
    }
  }

  "C1b the backend's process-version labels" should {
    Seq("upstream", "workspace-stack").foreach { which =>
      s"build every label of the $which config without the fallback (ECC, CCMO: dead keys)" in {
        val labels = """\s+([A-Z_]+): "([0-9.]+)"""".r.findAllMatchIn(Fixtures.text(s"contract/backend/eda-process-versions.$which.yaml"))
          .map(m => m.group(1) -> m.group(2)).toList
        val (known, dead) = labels.partition(l => Try(EbMsMessageType.withName(l._1)).isSuccess)
        dead.map(_._1).toSet shouldBe Set("ANFORDERUNG_ECC", "ANFORDERUNG_CCMO")
        known.foreach { case (code, label) =>
          val sample = ProtocolCatalog.outbound.find(_.code == code).get.message.copy(messageCodeVersion = Some(label))
          val (built, logs) = LogCapture("at.energydash.domain.eda.EdaMessage")(MessageHelper.getEdaMessageByType(sample))
          withClue(s"$code $label: ")(built.isDefined shouldBe true)
          withClue(s"$code $label falls back: ")(LogCapture.messages(logs).exists(_.startsWith("Unknown version label")) shouldBe false)
        }
      }
    }
  }

  "C2 eda/response/<tenant>/protocol/<process> (eda-xp → backend)" should {
    val published = ProtocolCatalog.inbound.map(r => r.fileName -> parse(Fixtures.text(s"protocol/golden/${r.golden}")).toOption.get)
    "use only keys the backend's structs know (case-insensitive: ResponseData's keys are capitalised on our side)" in {
      published.flatMap { case (_, j) => keys(j) }.toSet.filterNot(k => backendTags(k.toLowerCase)) shouldBe empty
    }
    "carry every metering-point factor as a whole number (the backend's Meter.PartFact is int)" in {
      def factors(j: Json): Seq[Json] = j.fold(Nil, _ => Nil, _ => Nil, _ => Nil, _.flatMap(factors),
        o => o.toList.flatMap { case (k, v) => (if (k == "partFact") Seq(v) else Nil) ++ factors(v) })
      published.flatMap { case (_, j) => factors(j) }.foreach(f => f.asNumber.flatMap(_.toLong).isDefined shouldBe true)
    }
  }

  "C3 topics" should {
    def matches(filter: String, topic: String): Boolean = {
      val (f, t) = (filter.split('/'), topic.split('/'))
      f.zipAll(t, null, null).forall { case (a, b) => a == "#" || (a != null && b != null && (a == "+" || a == b)) } ||
        (f.last == "#" && f.init.zip(t).forall { case (a, b) => a == "+" || a == b })
    }
    val paths = new MqttPaths {}
    "match the backend's protocol subscription for every process" in {
      ProtocolCatalog.codes.map(_.process).distinct.foreach(p =>
        withClue(p)(matches("eda/response/+/protocol/#", paths.edaReqResPath("RC100401", p)) shouldBe true))
    }
    "match the backend's error subscriptions (decode errors) and the protocol one (tenant errors)" in {
      matches("eda/response/error", at.energydash.config.Config.errorTopic) shouldBe true
      matches("eda/response/+/protocol/#", paths.edaReqResPath("RC100401", "error")) shouldBe true
    }
  }
}
