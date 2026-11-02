package at.energydash.protocol

import at.energydash.domain.XmlParseHandler
import at.energydash.domain.XmlParseHandler.ParseHeader
import at.energydash.testsupport.{Fixtures, Golden, ProtocolCatalog, Xsd}
import io.circe.generic.auto._
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * One test per inbound row of the protocol catalog: the fixture is valid against the XSD of its namespace,
 * `XmlParseHandler` (where `/pontonxp/message` and `/admin/upload` end) maps it to the expected message
 * code, and the message as published on MQTT equals its golden JSON.
 */
class InboundProtocolSpec extends AnyWordSpec with Matchers {
  import at.energydash.domain.JsonImplicit._

  "Inbound protocol" should {
    ProtocolCatalog.inbound.foreach { row =>
      row.name in {
        val root = Fixtures.xml(row.fixture)
        val doc = Fixtures.ebUtilitiesDocument(root)
        doc.foreach(d => Xsd.validate(d, Xsd.forNamespace(d.namespace)))
        val message = XmlParseHandler.mapXmlToEbms(
          ParseHeader("ATSENDER", "RCRECEIVER", Some("CONVERSATION"), Some(row.code)), doc.getOrElse(root))
        message.messageCode.toString shouldBe row.code
        Golden.check(row.golden, Seq(message.asJson.deepDropNullValues.spaces2SortKeys + "\n"))
      }
    }
  }
}
