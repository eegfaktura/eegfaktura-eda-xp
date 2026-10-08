package at.energydash.interfaces

import at.energydash.testsupport.{ProtocolCatalog, SoapCapture}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

/** The SOAP request to the Ponton messenger: address, transport headers and the OutboundDocument header. */
class SoapOutSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  private val row = ProtocolCatalog.outbound.find(r => r.code == "ANFORDERUNG_ECON" && r.label.contains("02.40")).get

  "The SOAP request" should {
    "go to app.kepserver.url as text/xml with the OutboundDocument SOAPAction" in {
      val c = SoapCapture.send(row.message)
      c.uri.toString shouldBe "http://127.0.0.1:16060/ponton/eda/webservice/outbound"
      c.contentType should startWith ("text/xml")
      c.headers.collectFirst { case (k, v) if k.equalsIgnoreCase("SOAPAction") => v } .map(_.replace("\"", "")) shouldBe
        Some("http://xp.ponton.de/eda/v320/outboundDocument")
    }
    "carry the OutboundDocument header in the order and shape of OutHeaderType" in {
      val c = SoapCapture.send(row.message.copy(seqNr = Some(42)))
      val h = (c.envelope \\ "OutboundDocument" \ "Header").head.child.collect { case e: scala.xml.Elem => e.label }
      h shouldBe Seq("MessageId", "SenderId", "ReceiverId", "MessageType", "MessageVersion", "LogInfo")
      c.pontonHeader("MessageId") shouldBe "MSG0000000042@RC100401"
      c.pontonHeader("LogInfo") shouldBe "VFEEG-OUT"
    }
    "use MSG<10000>@<sender> when the request carries no sequence number (pinned)" in {
      SoapCapture.send(row.message.copy(seqNr = None)).pontonHeader("MessageId") shouldBe "MSG0000010000@RC100401"
    }
  }
}
