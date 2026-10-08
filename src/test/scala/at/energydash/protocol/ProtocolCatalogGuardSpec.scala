package at.energydash.protocol

import at.energydash.domain.eda.MessageHelper
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.testsupport.ProtocolChecks
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Guards of the protocol catalog (protocol-changes.md §4): code, schemas, fixtures and the official
 * interface list on one side, the catalog on the other. A protocol change that is not in the catalog fails
 * here by name — the catalog is data, the guards (`ProtocolChecks`) read the code's behaviour and the files.
 * `ProtocolSelfTestSpec` proves that every guard complains about a broken catalog.
 */
class ProtocolCatalogGuardSpec extends AnyWordSpec with Matchers {

  "The protocol catalog" should {
    ProtocolChecks.guards.foreach { case (name, guard) =>
      s"pass $name" in {
        guard(ProtocolChecks.real) shouldBe empty
      }
    }
  }

  "Guard 6 — message codes" should {
    "throw for ERROR_MESSAGE (pinned: it feeds 261005-ca11)" in {
      an[RuntimeException] should be thrownBy MessageHelper.EDAMessageCodeToProcessCode(EbMsMessageType.ERROR_MESSAGE)
    }
  }
}
