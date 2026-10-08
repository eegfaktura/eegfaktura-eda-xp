package at.energydash.protocol

import at.energydash.domain.eda.MessageHelper
import at.energydash.testsupport.KnownError.knownError
import at.energydash.testsupport.ProtocolCatalog.Outbound
import at.energydash.testsupport._
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.LocalDate
import scala.util.Try

/**
 * One test per outbound row of the protocol catalog: the builder `getVersion` chooses, the Ponton header,
 * the document valid against its XSD, and document + header equal to their golden files.
 */
class OutboundProtocolSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  private def check(row: Outbound): Unit = {
    val message = row.message
    val (built, logs) = LogCapture("at.energydash.domain.eda.EdaMessage")(MessageHelper.getEdaMessageByType(message))
    assert(built.isDefined, s"no builder for ${row.code}")
    assert(!LogCapture.messages(logs).exists(_.startsWith("Unknown version label")), "label refused")
    row.builder.foreach(b => assert(built.get.getClass.getSimpleName == b, "builder chosen by getVersion"))

    val before = Normalise.dateTokens(LocalDate.now(Normalise.Vienna))
    val captured = SoapCapture.send(message)
    val after = Normalise.dateTokens(LocalDate.now(Normalise.Vienna))

    row.headerType.foreach(t => assert(captured.pontonHeader("MessageType") == t, "Ponton header MessageType"))
    row.headerVersion.foreach(v => assert(captured.pontonHeader("MessageVersion") == v, "Ponton header MessageVersion"))
    row.xsd.foreach(x => Xsd.validate(captured.document, x))

    // A row of a known error states the correct outcome by its assertions; no golden file from wrong output.
    if (row.knownError.isEmpty) {
      Golden.check(row.goldenBase + ".header", Seq(Normalise.header(captured.pontonHeader)))
      Golden.check(row.goldenBase + ".xml", Seq(before, after).distinct.map(d => Normalise.document(captured.document, d)))
    }
  }

  /** No label or a label without a case: no document, the ERROR names the label, nothing is sent (1.0.8). */
  private def refused(row: Outbound): Unit = {
    val (built, logs) = LogCapture("at.energydash.domain.eda.EdaMessage")(MessageHelper.getEdaMessageByType(row.message))
    assert(built.isEmpty, "a refused label must not build a document")
    val label = row.label.getOrElse("<none>")
    assert(LogCapture.messages(logs).exists(_.startsWith(s"Unknown version label $label for ${row.code}")), "ERROR naming the label")
    assert(Try(SoapCapture.send(row.message)).isFailure, "must fail, not send")
  }

  private def run(row: Outbound): Unit =
    if (row.refused) refused(row)
    else if (row.expectFailure) assert(Try(SoapCapture.send(row.message)).isFailure, "must fail, not send")
    else check(row)

  "Outbound protocol" should {
    ProtocolCatalog.outbound.foreach { row =>
      row.name in {
        row.knownError match {
          case None => run(row)
          case Some(id) => knownError(id)(run(row))
        }
      }
    }
  }
}
