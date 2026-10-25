package at.energydash.actors.soap

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.util.FastFuture
import at.energydash.actors.PontonService.buildMessageId
import at.energydash.actors.http.{AkkaHttpClients, AkkaHttpHandler}
import at.energydash.domain.eda.MessageHelper
import at.energydash.domain.enums.{EbMsMessageType, MeterDirectionType}
import at.energydash.domain.{EbMsMessage, Meter}
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike
import ponton.{OutboundMessage, OutHeaderType}
import soapenvelope11.Envelope

import scala.concurrent.Future
import scala.xml.NodeSeq

class PontonRequestSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with BeforeAndAfterAll with MockFactory {

  import scala.concurrent.ExecutionContext.Implicits.global

  trait MockClientHandler extends AkkaHttpHandler with AkkaHttpClients {
    val mock = mockFunction[HttpRequest, Future[HttpResponse]]

    override def sendRequest(httpRequest: HttpRequest)(implicit actorSystem: ActorSystem[_]): Future[HttpResponse] =
      mock(httpRequest)
  }

  "Send Soap Request" should {
    "handle Online Request message" in {
      val json = """{"id": 1}"""
      val response = FastFuture.successful {
        HttpResponse(
          status = StatusCodes.OK,
          entity = HttpEntity(ContentTypes.`application/json`, json))
      }

      val testService = (new PontonRequest(testKit.system) with MockClientHandler)
      testService.mock
        .expects ( where {
          (request: HttpRequest) => request.uri == Uri("http://127.0.0.1:16060/ponton/eda/webservice/outbound")
        })
        .returning(Future.successful(HttpResponse(status=StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, NodeSeq.Empty.toString()))))
      val edaMessage = EbMsMessage(
        conversationId = "AT003000202303041506076450000003761",
        requestId = Some("5JWLV5Z3"),
        messageId = Some("RC100181202303041506080740000003762"),
        sender = "RC100130", receiver = "AT003000", messageCode = EbMsMessageType.ONLINE_REG_INIT, messageCodeVersion = Some("02.00"),
        meter = Some(Meter("AT0030000000000000000000000655856", Some(MeterDirectionType.CONSUMPTION))), ecId = Some("AT00300000000RC100181000000956509"))

      val header = OutHeaderType(
        MessageId = buildMessageId(edaMessage.sender, edaMessage.seqNr.getOrElse(10000)),
        SenderId = edaMessage.sender,
        ReceiverId = edaMessage.receiver,
        MessageVersion = "07.00",
        MessageType = "CustomerMeteringPointRequest",
        LogInfo = Some("VFEEG-OUT"))

      val message = OutboundMessage(
        outboundmessageoption = MessageHelper.getEdaMessageByType(edaMessage).get.toRecord
      )

      whenReady(testService.service.sendRequest(edaMessage)) { e =>
        e shouldBe a [Envelope]
      }

    }
  }
}
