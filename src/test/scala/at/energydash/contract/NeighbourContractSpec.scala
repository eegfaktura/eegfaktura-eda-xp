package at.energydash.contract

import at.energydash.testsupport.{Fixtures, ProtocolCatalog, SoapCapture, Xsd}
import io.circe.Json
import io.circe.parser.parse
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.{File, StringReader}
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import scala.io.Source.fromFile
import scala.util.Using
import scala.xml.XML

/** Contracts with the energystore (CR_MSG), the gRPC callers (protos) and the Ponton messenger (SOAP). */
class NeighbourContractSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  private def shape(j: Json, path: String = ""): Set[String] = j.fold(Set(s"$path:null"), _ => Set(s"$path:bool"),
    _ => Set(s"$path:number"), _ => Set(s"$path:string"), a => a.headOption.map(shape(_, path + "[]")).getOrElse(Set(s"$path:[]")),
    o => o.toMap.flatMap { case (k, v) => shape(v, s"$path.$k") }.toSet)

  "C4 CR_MSG (eda-xp → energystore)" should {
    val ours = ProtocolCatalog.inbound.filter(_.code == "DATEN_CRMSG")
      .map(r => r.fileName -> parse(Fixtures.text(s"protocol/golden/${r.golden}")).toOption.get)
    val goSource = Fixtures.text("contract/energystore/mqtt.go.txt")
    val read = """json:"([A-Za-z]+)""".r.findAllMatchIn(goSource).map(_.group(1)).toSet - "message"

    "carry every key the energystore's parser reads" in {
      ours.foreach { case (name, j) =>
        val present = shape(j).flatMap(_.split(':').head.split('.').map(_.stripSuffix("[]")))
        // direction is optional (omitempty); ecId is absent when the document has none and base.eeg has no row
        // for the receiver (scenario S8, pinned) — the energystore then gets "" (its struct has no omitempty)
        withClue(name)(read.diff(present).diff(Set("direction", "ecId")) shouldBe empty)
      }
    }
    "have the shape of the energystore's own fixtures (keys and value types)" in {
      val theirs = Seq("mock-cr-msg.json", "v3world-first-crmsg.json").map(f => shape(parse(Fixtures.text(s"contract/energystore/$f")).toOption.get))
      val relevant = (s: Set[String]) => s.filter(p => read.exists(k => p.split(':').head.endsWith(s".$k") || p.contains(s".$k[]")))
      ours.foreach { case (name, j) => theirs.foreach(t => withClue(name)(relevant(shape(j)).diff(relevant(t)) shouldBe empty)) }
    }
  }

  "C5/C6 gRPC protos" should {
    def normalise(proto: String): String = proto.linesIterator.map(_.replaceAll("//.*", "").trim).filter(_.nonEmpty).mkString("\n")
    def ours(name: String) = Using.resource(fromFile(new File(s"src/main/protobuf/$name")))(_.mkString)
    "equal admin-backend's ponton.proto" in {
      normalise(Fixtures.text("contract/admin-backend/ponton.proto")) shouldBe normalise(ours("ponton.proto"))
    }
    "equal the backend's mail.proto" in {
      normalise(Fixtures.text("contract/backend/mail.proto")) shouldBe normalise(ours("mail.proto"))
    }
  }

  "C7 SOAP to Ponton" should {
    "send the SOAPAction of OutboundDocument.wsdl" in {
      val wsdl = XML.loadFile(new File("src/main/wsdl/OutboundDocument.wsdl"))
      val action = (wsdl \\ "operation").flatMap(_.attribute("soapAction")).map(_.text).head
      val row = ProtocolCatalog.outbound.find(r => r.code == "ANFORDERUNG_ECP" && r.label.contains("02.10")).get
      SoapCapture.send(row.message).headers.collectFirst { case (k, v) if k.equalsIgnoreCase("SOAPAction") => v.replace("\"", "") } shouldBe Some(action)
    }
  }

  "C8 inbound envelopes (Ponton → eda-xp)" should {
    // InboundDocument.xsd references the document elements without importing their schemas: validate with
    // a schema set of InboundDocument.xsd plus the ebUtilities schemas it references.
    // Xerces needs an <import> per referenced namespace, which the vendor file lacks: validate against a copy
    // with the imports added (target/test-tmp), the vendor file itself stays untouched.
    lazy val schema = {
      val vendor = Using.resource(fromFile(new File(Xsd.Dir, "InboundDocument.xsd")))(_.mkString)
      val prefixes = """xmlns:([a-z0-9]+)="(http://www\.ebutilities\.at/schemata/[^"]+)"""".r.findAllMatchIn(vendor).map(_.group(2)).toSeq.distinct
      val imports = prefixes.flatMap(ns => Xsd.byNamespace.get(ns).map(f =>
        s"""<import namespace="$ns" schemaLocation="${new File(Xsd.Dir, f).getAbsoluteFile.toURI}"/>""")).mkString("\n")
      val withImports = vendor.replaceFirst("(<import schemaLocation=\"\\.\\./xsd/ebUtilities\\.xsd\"[^>]*/>)",
        "$1\n" + java.util.regex.Matcher.quoteReplacement(imports))
        .replace("../xsd/ebUtilities.xsd", new File(Xsd.Dir, "ebUtilities.xsd").getAbsoluteFile.toURI.toString)
      val copy = new File("target/test-tmp/InboundDocument-with-imports.xsd")
      copy.getParentFile.mkdirs()
      java.nio.file.Files.writeString(copy.toPath, withImports)
      SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(copy)
    }
    "be valid InboundDocuments for every via = ponton catalog row" in {
      ProtocolCatalog.inbound.filter(_.via == "ponton").foreach { row =>
        val doc = Fixtures.ebUtilitiesDocument(Fixtures.xml(row.fixture)).get
        val (s, r) = Fixtures.routing(doc)
        val inbound = (XML.loadString(Fixtures.inboundEnvelope(doc, s, r, row.code)) \\ "InboundDocument").head
        withClue(row.name)(schema.newValidator().validate(new StreamSource(new StringReader(inbound.toString))))
      }
    }
  }
}
