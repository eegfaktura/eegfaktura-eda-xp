package at.energydash.protocol

import at.energydash.testsupport.KnownError.knownError
import at.energydash.testsupport.ProtocolCatalog.Outbound
import at.energydash.testsupport._
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.LocalDate

/**
 * One test per outbound row of the protocol catalog: the builder `getVersion` chooses, the Ponton header,
 * the document valid against its XSD, and document + header equal to their golden files.
 */
class OutboundProtocolSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  private def run(row: Outbound): Unit = {
    val before = Normalise.dateTokens(LocalDate.now(Normalise.Vienna))
    val result = ProtocolChecks.run(row)
    val after = Normalise.dateTokens(LocalDate.now(Normalise.Vienna))
    val problems = ProtocolChecks.outbound(row, result)
    assert(problems.isEmpty, problems.mkString("\n"))
    // A row of a known error states the correct outcome by its checks; no golden file from wrong output.
    if (row.sends && row.knownError.isEmpty) {
      val captured = result.sent.get
      Golden.check(row.goldenBase + ".header", Seq(Normalise.header(captured.pontonHeader)))
      Golden.check(row.goldenBase + ".xml", Seq(before, after).distinct.map(d => Normalise.document(captured.document, d)))
    }
  }

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
