package at.energydash.protocol

import at.energydash.domain.EbMsMessage
import at.energydash.domain.XmlParseHandler
import at.energydash.domain.XmlParseHandler.ParseHeader
import at.energydash.domain.eda.MessageHelper
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.mqtt.path.MqttPaths
import at.energydash.testsupport.{Fixtures, Golden, ProtocolCatalog, Xsd}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Try
import scala.xml.XML

/**
 * Guards of the protocol catalog (protocol-changes.md §4): code, schemas, fixtures and the official
 * interface list on one side, the catalog on the other. A protocol change that is not in the catalog fails
 * here by name — the catalog is data, these tests read the code's behaviour and the files.
 */
class ProtocolCatalogGuardSpec extends AnyWordSpec with Matchers {
  import ProtocolCatalog._

  private val ebUtilitiesSchemas: Map[String, String] =
    Xsd.byNamespace.filter(_._1.startsWith("http://www.ebutilities.at/schemata/"))

  private def inboundNamespace(row: Inbound): Option[String] =
    Fixtures.ebUtilitiesDocument(Fixtures.xml(row.fixture)).map(_.namespace)

  private lazy val inboundNamespaces: Set[String] = inbound.flatMap(inboundNamespace).toSet

  private def anyMessage(code: EbMsMessageType.Value) =
    EbMsMessage(messageId = Some("M1"), conversationId = "C1", sender = "RC100401", receiver = "AT003000",
      messageCode = code, requestId = Some("R1"))

  "Guard 0 — unique rows" should {
    "give every outbound row and every inbound fixture its own test name" in {
      outbound.map(_.name).diff(outbound.map(_.name).distinct) shouldBe empty
      inbound.map(_.fixture).diff(inbound.map(_.fixture).distinct) shouldBe empty
    }
  }

  "Guard 1 — builders" should {
    "have outbound rows exactly for the message codes eda-xp can build" in {
      val buildable = EbMsMessageType.values.toList.filter(v => Try(MessageHelper.getEdaMessageByType(anyMessage(v))).toOption.flatten.isDefined
        || outbound.exists(_.code == v.toString)).map(_.toString).toSet
      val withRows = outbound.map(_.code).toSet
      withClue("codes with a builder but no catalog row: ")(buildable.diff(withRows) shouldBe empty)
      withClue("catalog rows for codes without a builder: ")(withRows.diff(buildable) shouldBe empty)
    }
    "pin the refusal (no label and an unknown label) of every code with version cases" in {
      outbound.map(_.code).distinct.filterNot(_ == "ANFORDERUNG_GN").foreach { code =>
        withClue(s"$code without label: ")(outbound.exists(r => r.code == code && r.refused && r.label.isEmpty) shouldBe true)
        withClue(s"$code with an unknown label: ")(outbound.exists(r => r.code == code && r.refused && r.label.nonEmpty) shouldBe true)
      }
    }
  }

  "Guard 3 — schemas" should {
    "account for every ebUtilities process schema in src/main/xsd" in {
      val accounted = outbound.flatMap(_.xsd).map(x => ebUtilitiesSchemas.find(_._2 == x).map(_._1).getOrElse(x)).toSet ++
        inboundNamespaces ++ notParsed ++ typeLibraries
      val missing = ebUtilitiesSchemas.filterNot { case (ns, _) => accounted(ns) }.values.toList.sorted
      withClue("XSDs without a catalog row, a not-parsed entry or a type-library entry: ")(missing shouldBe empty)
    }
  }

  "Guard 4 — parser" should {
    "parse every outbound document whose namespace has inbound rows, and only those" in {
      outbound.filter(r => r.sends && r.knownError.isEmpty && r.xsd.isDefined).foreach { row =>
        val built = MessageHelper.getEdaMessageByType(row.message).get
        val doc = XML.loadString(built.toXML.toString)
        val parsed = XmlParseHandler.mapXmlToEbms(ParseHeader("S", "R", Some("C"), Some(row.code)), doc)
        val ns = Xsd.byNamespace.find(_._2 == row.xsd.get).get._1
        if (inboundNamespaces(ns)) withClue(row.name)(parsed.messageCode.toString shouldBe row.code)
        else withClue(row.name)(parsed.errorMessage.getOrElse("") should startWith("Unknown or not registered MessageType"))
      }
    }
  }

  "Guard 5 — golden files" should {
    "belong to a row" in {
      val expected = outbound.filter(r => r.sends && r.knownError.isEmpty).flatMap(r => Seq(r.goldenBase + ".xml", r.goldenBase + ".header")).toSet ++
        inbound.map(_.golden)
      withClue("orphan golden files: ")((Golden.all -- expected) shouldBe empty)
    }
  }

  "Guard 6 — message codes" should {
    "give every message code a process, an MQTT topic and, for requests, the Ponton header type" in {
      val paths = new MqttPaths {}
      val values = EbMsMessageType.values.toList.map(_.toString).filterNot(_ == "ERROR_MESSAGE")
      withClue("codes without a `codes` row: ")(values.diff(codes.map(_.code)) shouldBe empty)
      codes.foreach { c =>
        val code = EbMsMessageType.withName(c.code)
        withClue(c.code)(MessageHelper.EDAMessageCodeToProcessCode(code).toString shouldBe c.process)
        withClue(c.code)(paths.edaReqResPath("RC100401", c.process) shouldBe s"eda/response/rc100401/protocol/${c.process.toLowerCase}")
        c.headerType.foreach(t => withClue(c.code)(MessageHelper.pontonHeaderMessageType(code) shouldBe t))
      }
    }
    "throw for ERROR_MESSAGE (pinned: it feeds 261005-ca11)" in {
      an[RuntimeException] should be thrownBy MessageHelper.EDAMessageCodeToProcessCode(EbMsMessageType.ERROR_MESSAGE)
    }
  }

  "Guard 7 — Ponton path" should {
    "admit exactly the via = ponton rows in InboundDocument.xsd's message choice" in {
      val choice = XML.loadFile(new java.io.File(Xsd.Dir, "InboundDocument.xsd"))
      val prefixes = choice.scope
      val admitted = (choice \\ "element").flatMap(_.attribute("ref")).map(_.text).flatMap { ref =>
        ref.split(':') match { case Array(p, local) => Option(prefixes.getURI(p)).map(_ -> local); case _ => None }
      }.toSet
      inbound.foreach { row =>
        val doc = Fixtures.ebUtilitiesDocument(Fixtures.xml(row.fixture))
        doc.foreach { d =>
          val key = d.namespace -> d.label
          row.via match {
            case "ponton" => withClue(row.name)(admitted(key) shouldBe true)
            case "upload" => withClue(row.name)(admitted(key) shouldBe false)
            case other => fail(s"${row.name}: via must be ponton or upload for an ebUtilities document, not $other")
          }
        }
      }
    }
  }

  "Guard 8 — official interface list" should {
    val current = official.filter(_.validOn(referenceDate))

    "have a specific outbound row for every official request eda-xp sends (schema set and schema as published)" in {
      val missing = current.filter(o => o.sentByEdaXp && !unsupported.contains(o.code)).filterNot { o =>
        outbound.exists(r => r.code == o.code && r.sends && r.knownError.isEmpty &&
          r.headerVersion.contains(o.schemaSetVersion) && r.xsd.contains(o.schema + ".xsd"))
      }.map(o => s"${o.code} ${o.schemaSet} ${o.schema}")
      withClue(s"official requests (${config.getString("official.release")}) without a matching row: ")(missing shouldBe empty)
    }
    "parse every official document eda-xp receives (schema covered by an inbound row, code known)" in {
      val received = current.filter(o => o.receivedByEdaXp && !unsupported.contains(o.code))
      val noSchema = received.filterNot(o => inboundNamespaces(o.namespace)).map(o => s"${o.code} ${o.schema}")
      val noCode = received.filterNot(o => Try(EbMsMessageType.withName(o.code)).isSuccess).map(_.code)
      withClue("official answers whose schema has no inbound row: ")(noSchema shouldBe empty)
      withClue("official message codes unknown to EbMsMessageType: ")(noCode shouldBe empty)
    }
    "list only codes of the official processes as unsupported" in {
      unsupported.keySet.diff(official.map(_.code).toSet) shouldBe empty
    }
  }

  "Guard 9 — Ponton header rule" should {
    "send the schema set's message type and version, or the process code only where pinned" in {
      outbound.filter(_.sends).foreach { r =>
        val process = codes.find(_.code == r.code).get.process
        val labelKey = r.label.getOrElse("none")
        val expectedType = headerProcessCode.get(r.code) match {
          case Some(labels) if labels(labelKey) => process
          case Some(_) => r.code   // a new label of a pinned code must switch to the message type
          case None => r.code
        }
        withClue(s"${r.name} MessageType: ")(r.headerType shouldBe Some(expectedType))
        r.label.foreach(l => withClue(s"${r.name} MessageVersion: ")(r.headerVersion shouldBe Some(l)))
      }
    }
    "agree with the header type of the codes table and pin only codes with requests" in {
      headerProcessCode.keySet.diff(outbound.map(_.code).toSet) shouldBe empty
      codes.filter(_.headerType.isDefined).foreach { c =>
        val expected = if (headerProcessCode.contains(c.code)) c.process else c.code
        withClue(c.code)(c.headerType shouldBe Some(expected))
      }
    }
  }
}
