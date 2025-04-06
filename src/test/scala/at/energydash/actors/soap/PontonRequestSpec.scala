package at.energydash.actors.soap

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.util.FastFuture
import at.energydash.actors.PontonService.buildMessageId
import at.energydash.actors.http.{AkkaHttpClients, AkkaHttpHandler}
import at.energydash.domain.eda.MessageHelper
import at.energydash.domain.enums.{EbMsMessageType, MeterDirectionType}
import at.energydash.domain.{EbMsMessage, Meter}
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike
import ponton.{Message2, OutHeaderType}
import soapenvelope11.Envelope

import scala.concurrent.Future
import scala.xml.NodeSeq

class PontonRequestSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with BeforeAndAfterAll with MockFactory {

  import scala.concurrent.ExecutionContext.Implicits.global

  //  implicit val as = ActorSystem()
  //  implicit val mat = ActorMaterializer()
  //  implicit val ec = as.dispatcher
  //
  //  override def afterAll(): Unit = as.terminate()

  //  def runWithContext[T](f: ActorContext[T] => Assertion): Assertion = {
  //    def extractor(replyTo: ActorRef[Assertion]): Behavior[T] =
  //      Behaviors.setup { context =>
  //        replyTo ! f(context)
  //
  //        Behaviors.ignore
  //      }
  //    val probe = testKit.createTestProbe[Assertion]()
  //    testKit.spawn(extractor(probe.ref))
  //    probe.receiveMessage(1.minute)
  //  }

  trait MockClientHandler extends AkkaHttpHandler with AkkaHttpClients {
    val mock = mockFunction[HttpRequest, Future[HttpResponse]]

    override def sendRequest(httpRequest: HttpRequest)(implicit actorSystem: ActorSystem[_]): Future[HttpResponse] =
      mock(httpRequest)
  }

  "Send Soap Request" should {
    "handle Online Request message" in /*runWithContext[NotUsed] { context => */ {
      //      import context._
      //      val mockHttp: HttpExt = mock[HttpExt]
      //
      //      class TestPontonRequest(ctx: ActorContext[_]) extends PontonRequest(testKit.system) {
      //        override val http: HttpExt = mockHttp
      //      }

      val json = """{"id": 1}"""
      val response = FastFuture.successful {
        HttpResponse(
          status = StatusCodes.OK,
          entity = HttpEntity(ContentTypes.`application/json`, json))
      }
      //      Mockito.when(mockHttp.singleRequest(any[HttpRequest], any[HttpsConnectionContext], any[ConnectionPoolSettings], any[LoggingAdapter]))
      //        .thenReturn(response)
      //      val testService = new TestPontonRequest(context).service

      val testService = (new PontonRequest(testKit.system) with MockClientHandler)
      testService.mock
        //        .expects(HttpRequest(uri = "http://dummy.restapiexample.com/api/v1/employees"))
        .expects ( where {
          (request: HttpRequest) => request.uri == Uri("http://10.10.10.51:6060/ponton/eda/webservice/outbound")
        })
//        .returning(Future.successful(HttpResponse(entity = HttpEntity(ByteString("stripString")))))
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

      val message = Message2(
        message2option = MessageHelper.getEdaMessageByType(edaMessage).get.toRecord
      )

      whenReady(testService.service.sendRequest(edaMessage)) { e =>
        e shouldBe a [Envelope]
      }

    }
  }
}
