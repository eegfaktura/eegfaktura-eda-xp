package at.energydash.protocol

import at.energydash.testsupport.ProtocolCatalog.Outbound
import at.energydash.testsupport.ProtocolChecks.CatalogData
import at.energydash.testsupport._
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

/**
 * Tests of the protocol tests: every guard and the outbound row check run on a deliberately broken copy of
 * the real catalog (or a wrong row expectation) and must name the problem. A check that silently stops
 * checking — a condition that is always true, a filter that drops every row — fails here, while the
 * protocol specs themselves would stay green.
 */
class ProtocolSelfTestSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  private val real = ProtocolChecks.real

  private def row(code: String, label: String): Outbound =
    real.outbound.find(r => r.code == code && r.label.contains(label) && r.variant.isEmpty).get

  private def replaceRow(d: CatalogData, old: Outbound, updated: Outbound): CatalogData =
    d.copy(outbound = d.outbound.map(r => if (r == old) updated else r))

  /** The guard passes on the real data and names `expected` for the broken copy. */
  private def complains(guard: CatalogData => List[String], broken: CatalogData, expected: String): Unit = {
    assert(guard(real).isEmpty, s"the guard fails on the real catalog: ${guard(real)}")
    val problems = guard(broken)
    assert(problems.exists(_.contains(expected)), s"expected a problem containing '$expected', got: $problems")
  }

  "Guard 0 — unique rows" should {
    "report a duplicated outbound row and a duplicated inbound fixture" in {
      complains(ProtocolChecks.uniqueRows, real.copy(outbound = real.outbound :+ real.outbound.head), "duplicate outbound row")
      complains(ProtocolChecks.uniqueRows, real.copy(inbound = real.inbound :+ real.inbound.head), "duplicate inbound fixture")
    }
  }

  "Guard 1 — builders" should {
    "report a code with a builder but without rows" in {
      complains(ProtocolChecks.builders, real.copy(outbound = real.outbound.filterNot(_.code == "ANFORDERUNG_ECP")),
        "code with a builder but no catalog row: ANFORDERUNG_ECP")
    }
    "report rows for a code without a builder" in {
      complains(ProtocolChecks.builders, real.copy(outbound = real.outbound :+ row("ANFORDERUNG_ECP", "02.10").copy(code = "ANTWORT_ECON")),
        "catalog rows for a code without a builder: ANTWORT_ECON")
    }
    "report a code without the refused row for a missing or an unknown label" in {
      complains(ProtocolChecks.refusals, real.copy(outbound = real.outbound.filterNot(r => r.code == "ANFORDERUNG_ECON" && r.refused && r.label.isEmpty)),
        "ANFORDERUNG_ECON: no refused row without label")
      complains(ProtocolChecks.refusals, real.copy(outbound = real.outbound.filterNot(r => r.code == "ANFORDERUNG_CPF" && r.refused && r.label.nonEmpty)),
        "ANFORDERUNG_CPF: no refused row with an unknown label")
    }
  }

  "Guard 3 — schemas" should {
    "report an ebUtilities XSD nobody accounts for" in {
      complains(ProtocolChecks.schemas, real.copy(schemas = real.schemas + ("http://www.ebutilities.at/schemata/new/99p99" -> "New_99p99.xsd")),
        "New_99p99.xsd")
    }
    "report an XSD once its only entry (not-parsed) is gone" in {
      val outboundXsds = real.outbound.flatMap(_.xsd).toSet
      val ns = real.notParsed.find(n => real.schemas.get(n).exists(x => !outboundXsds(x))).get
      complains(ProtocolChecks.schemas, real.copy(notParsed = real.notParsed - ns), real.schemas(ns))
    }
  }

  "Guard 4 — parser" should {
    "report an outbound document whose namespace has inbound rows but parses to another code" in {
      val econ = row("ANFORDERUNG_ECON", "02.40")
      complains(ProtocolChecks.parser, replaceRow(real, econ, econ.copy(xsd = Some("ECMPList_01p10.xsd"))), "parsed as")
    }
    "report an outbound document parsed although its namespace has no inbound row" in {
      val cpf = row("ANFORDERUNG_CPF", "01.00")  // ECMPList 01p10: has inbound rows
      val ecmplist = real.schemas.find(_._2 == "ECMPList_01p10.xsd").get._1
      val withoutInbound = real.copy(inbound = real.inbound.filterNot(r =>
        Fixtures.ebUtilitiesDocument(Fixtures.xml(r.fixture)).exists(_.namespace == ecmplist)))
      complains(ProtocolChecks.parser, withoutInbound, s"${cpf.name}: parsed although")
    }
  }

  "Guard 5 — golden files" should {
    "report an orphan golden file, also the one left behind by a removed row" in {
      complains(ProtocolChecks.golden, real.copy(goldenFiles = real.goldenFiles + "out/ANFORDERUNG_ECON_02.50.xml"), "out/ANFORDERUNG_ECON_02.50.xml")
      val ecp = row("ANFORDERUNG_ECP", "02.10")
      complains(ProtocolChecks.golden, real.copy(outbound = real.outbound.filterNot(_ == ecp)), "out/ANFORDERUNG_ECP_02.10.header")
    }
  }

  "Guard 6 — message codes" should {
    "report a missing codes row, a wrong process and a wrong Ponton header type" in {
      complains(ProtocolChecks.codes, real.copy(codes = real.codes.filterNot(_.code == "SENDEN_ECP")), "code without a `codes` row: SENDEN_ECP")
      complains(ProtocolChecks.codes, real.copy(codes = real.codes.map(c => if (c.code == "SENDEN_ECP") c.copy(process = "EC_REQ_ONL") else c)),
        "SENDEN_ECP: process is EC_PODLIST")
      complains(ProtocolChecks.codes, real.copy(codes = real.codes.map(c => if (c.code == "ANFORDERUNG_ECP") c.copy(headerType = Some("EC_PODLIST")) else c)),
        "ANFORDERUNG_ECP: Ponton header type is ANFORDERUNG_ECP")
    }
  }

  "Guard 7 — Ponton path" should {
    "report a Ponton fixture marked upload and an upload fixture marked ponton" in {
      val ponton = real.inbound.find(_.via == "ponton").get
      val upload = real.inbound.find(_.via == "upload").get
      complains(ProtocolChecks.pontonPath, real.copy(inbound = real.inbound.map(r => if (r == ponton) r.copy(via = "upload") else r)), "via must be ponton")
      complains(ProtocolChecks.pontonPath, real.copy(inbound = real.inbound.map(r => if (r == upload) r.copy(via = "ponton") else r)), "not in InboundDocument.xsd")
    }
  }

  "Guard 8 — official interface list" should {
    def officialEcon = real.official.find(o => o.code == "ANFORDERUNG_ECON" && o.validOn(real.referenceDate)).get
    "report a new schema set version of a request (a new release)" in {
      val o = officialEcon
      complains(ProtocolChecks.officialRequests,
        real.copy(official = real.official.map(x => if (x == o) x.copy(schemaSet = "EC_REQ_ONL_02.50") else x)), "ANFORDERUNG_ECON EC_REQ_ONL_02.50")
    }
    "report a new schema of a request" in {
      val o = officialEcon
      complains(ProtocolChecks.officialRequests,
        real.copy(official = real.official.map(x => if (x == o) x.copy(schema = "CMRequest_01p40") else x)), "CMRequest_01p40")
    }
    "not accept a refused or known-error row as the matching row" in {
      val econ = row("ANFORDERUNG_ECON", "02.40")
      complains(ProtocolChecks.officialRequests, replaceRow(real, econ, econ.copy(knownError = Some("x"))), "ANFORDERUNG_ECON")
      complains(ProtocolChecks.officialRequests, replaceRow(real, econ, econ.copy(refused = true)), "ANFORDERUNG_ECON")
    }
    "report an official answer with a new schema and an unknown answer code" in {
      val answer = real.official.find(o => o.receivedByEdaXp && o.validOn(real.referenceDate) && !real.unsupported.contains(o.code)).get
      complains(ProtocolChecks.officialAnswers,
        real.copy(official = real.official.map(x => if (x == answer) x.copy(namespace = "http://www.ebutilities.at/schemata/new/99p99") else x)),
        "schema has no inbound row")
      complains(ProtocolChecks.officialAnswers,
        real.copy(official = real.official.map(x => if (x == answer) x.copy(code = "ANTWORT_NEU") else x)), "unknown to EbMsMessageType: ANTWORT_NEU")
    }
    "report an unsupported entry that is not an official code" in {
      complains(ProtocolChecks.unsupportedListed, real.copy(unsupported = real.unsupported + ("ANFORDERUNG_NEU" -> "x")), "ANFORDERUNG_NEU")
    }
  }

  "Guard 9 — Ponton header rule" should {
    "report a pinned code that keeps the process code with a new label" in {
      val pt = row("ANFORDERUNG_PT", "03.00")
      complains(ProtocolChecks.headerRule, real.copy(outbound = real.outbound :+ pt.copy(label = Some("03.10"), headerVersion = Some("03.10"))),
        "ANFORDERUNG_PT 03.10: MessageType CR_REQ_PT, rule says ANFORDERUNG_PT")
    }
    "report the process code for a code that is not pinned, and a version that is not the label" in {
      val ecp = row("ANFORDERUNG_ECP", "02.10")
      complains(ProtocolChecks.headerRule, replaceRow(real, ecp, ecp.copy(headerType = Some("EC_PODLIST"))), "rule says ANFORDERUNG_ECP")
      complains(ProtocolChecks.headerRule, replaceRow(real, ecp, ecp.copy(headerVersion = Some("01.00"))), "MessageVersion 01.00, rule says 02.10")
    }
    "report a codes header-type against the rule and a pin for a code without requests" in {
      complains(ProtocolChecks.headerCodes, real.copy(headerProcessCode = real.headerProcessCode - "ANFORDERUNG_PT"), "ANFORDERUNG_PT: codes header-type CR_REQ_PT")
      complains(ProtocolChecks.headerCodes, real.copy(headerProcessCode = real.headerProcessCode + ("ANTWORT_PT" -> Set("01.00"))), "without requests: ANTWORT_PT")
    }
  }

  "The outbound row check" should {
    lazy val econ = row("ANFORDERUNG_ECON", "02.40")
    lazy val econRun = ProtocolChecks.run(econ)
    def wrong(r: Outbound, expected: String, run: ProtocolChecks.OutboundRun = econRun): Unit = {
      val problems = ProtocolChecks.outbound(r, run)
      assert(problems.exists(_.contains(expected)), s"expected '$expected', got: $problems")
    }
    "pass the real row" in {
      ProtocolChecks.outbound(econ, econRun) shouldBe empty
    }
    "report a wrong builder, header type, header version and schema" in {
      wrong(econ.copy(builder = Some("CMRequestRegistrationOnlineXMLMessageV0200")), "builder CMRequestRegistrationOnlineXMLMessageV0230")
      wrong(econ.copy(headerType = Some("EC_REQ_ONL")), "header MessageType ANFORDERUNG_ECON, not EC_REQ_ONL")
      wrong(econ.copy(headerVersion = Some("03.00")), "header MessageVersion 02.40, not 03.00")
      wrong(econ.copy(xsd = Some("CMRequest_01p10.xsd")), "invalid against CMRequest_01p10.xsd")
    }
    "report a sent request where the row expects a refusal or a failure" in {
      wrong(econ.copy(refused = true), "refused label built")
      wrong(econ.copy(refused = true), "refused label was sent")
      wrong(econ.copy(expectFailure = true), "must fail, but was sent")
    }
    "report a refused label where the row expects a request" in {
      val refused = real.outbound.find(r => r.code == "ANFORDERUNG_ECON" && r.label.contains("09.99")).get
      wrong(refused.copy(refused = false), "not sent", ProtocolChecks.run(refused))
    }
  }

  "Golden.compare" should {
    "pass an equal output and one of the midnight candidates, and report a change and a missing file" in {
      Golden.compare("g", Some("a\nb"), Seq("a\nb")) shouldBe None
      Golden.compare("g", Some("a\nb"), Seq("a\nc", "a\nb")) shouldBe None
      Golden.compare("g", Some("a\nb"), Seq("a\nc")).get should include ("line 2")
      Golden.compare("g", None, Seq("a")).get should include ("missing")
    }
    "see the DateTo difference of 1.0.8 through the normalisation" in {
      val doc = ProtocolChecks.run(row("ANFORDERUNG_ECON", "02.40")).sent.get.document
      val normalised = Normalise.document(doc, Normalise.dateTokens())
      val old = normalised.replace("2099-12-31", "2100-01-31")
      assert(old != normalised, "the sample has no DateTo")
      Golden.compare("g", Some(old), Seq(normalised)) shouldBe defined
    }
  }
}
