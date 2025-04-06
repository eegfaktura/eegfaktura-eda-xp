package at.energydash.actors

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import at.energydash.EmbeddedDb
import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.EbMsMessageType
import org.scalatest.BeforeAndAfter
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.DurationInt

class PontonServiceSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with BeforeAndAfter with EmbeddedDb {

  "Ponton Service Actor" should {
    "Handle EDA SOAP Message with Error" in {
      val edaResponse = createTestProbe[EdaCommand]

      val pontonService = spawn(PontonService())
      val testMessage = EbMsMessage(messageId = Some("1234"), conversationId = "con",
        sender = "myeeg",
        receiver = "rec",
        messageCode = EbMsMessageType.EDA_MSG_AUFHEBUNG_CCMS,
        messageCodeVersion = Some("02.00"),
        requestId = Some("567890"))

      pontonService ! SendEdaCommand(testMessage, edaResponse.ref)

      edaResponse.receiveMessage(5.seconds)
    }

    "Handle EDA not existing SOAP Message" in {
      val edaResponse = createTestProbe[EdaCommand]

      val pontonService = spawn(PontonService())
      val testMessage = EbMsMessage(messageId = Some("1234"), conversationId = "con",
        sender = "myeeg",
        receiver = "rec",
        messageCode = EbMsMessageType.OFFLINE_REG_ANSWER,
        messageCodeVersion = Some("02.00"),
        requestId = Some("567890"))

      pontonService ! SendEdaCommand(testMessage, edaResponse.ref)

      edaResponse.receiveMessage(5.seconds)
    }
  }
}
