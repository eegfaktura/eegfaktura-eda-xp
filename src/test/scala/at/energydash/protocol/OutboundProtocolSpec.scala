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
    val built = MessageHelper.getEdaMessageByType(message)
    assert(built.isDefined, s"no builder for ${row.code}")
    row.builder.foreach(b => assert(built.get.getClass.getSimpleName == b, "builder chosen by getVersion"))

    val before = Normalise.dateTokens(LocalDate.now(Normalise.Vienna))
    val captured = SoapCapture.send(message)
    val after = Normalise.dateTokens(LocalDate.now(Normalise.Vienna))

    row.headerType.foreach(t => assert(captured.pontonHeader("MessageType") == t, "Ponton header MessageType"))
    row.headerVersion.foreach(v => assert(captured.pontonHeader("MessageVersion") == v, "Ponton header MessageVersion"))
    row.xsd.foreach(x => Xsd.validate(captured.document, x))

    Golden.check(row.goldenBase + ".header", Seq(Normalise.header(captured.pontonHeader)))
    Golden.check(row.goldenBase + ".xml", Seq(before, after).distinct.map(d => Normalise.document(captured.document, d)))
  }

  "Outbound protocol" should {
    ProtocolCatalog.outbound.foreach { row =>
      row.name in {
        row.knownError match {
          case None => check(row)
          case Some(id) => knownError(id) {
            if (row.expectFailure) assert(Try(SoapCapture.send(row.message)).isFailure, "must fail, not send")
            else check(row)
          }
        }
      }
    }
  }
}
