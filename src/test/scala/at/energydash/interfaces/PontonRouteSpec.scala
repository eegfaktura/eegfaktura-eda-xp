package at.energydash.interfaces

import at.energydash.actors.MqttPublisher.{MqttCommand, MqttPublish, MqttPublishError}
import at.energydash.actors.http.PontonRoute
import at.energydash.testsupport.KnownError.knownError
import at.energydash.testsupport.{Fixtures, ProtocolCatalog}
import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.stream.Materializer
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Await
import scala.concurrent.duration._

/** The HTTP entry of the Ponton messenger (`/pontonxp/…`), bound on loopback with a probe as MQTT publisher. */
class PontonRouteSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  private val publisher: TestProbe[MqttCommand] = createTestProbe[MqttCommand]()
  private val binding = Await.result(Http()(system).newServerAt("127.0.0.1", 0)
    .bind(new PontonRoute(publisher.ref)(system).pontonRoutes), 10.seconds)
  private val base = s"http://127.0.0.1:${binding.localAddress.getPort}/pontonxp"

  override def afterAll(): Unit = { Await.ready(binding.unbind(), 10.seconds); super.afterAll() }

  private def post(path: String, body: String, ct: ContentType.WithCharset = ContentTypes.`text/xml(UTF-8)`): HttpResponse =
    Await.result(Http()(system).singleRequest(HttpRequest(HttpMethods.POST, s"$base/$path", entity = HttpEntity(ct, body))), 10.seconds)
      .tap(_.discardEntityBytes()(Materializer(system)))

  private def get(path: String): HttpResponse =
    Await.result(Http()(system).singleRequest(HttpRequest(uri = s"$base/$path")), 10.seconds).tap(_.discardEntityBytes()(Materializer(system)))

  implicit class Tap[A](a: A) { def tap(f: A => Unit): A = { f(a); a } }

  private val process = ProtocolCatalog.codes.map(c => c.code -> c.process).toMap

  "POST /pontonxp/message" should {
    ProtocolCatalog.inbound.filter(_.via == "ponton").foreach { row =>
      s"publish ${row.fileName} as ${row.code}" in {
        val doc = Fixtures.ebUtilitiesDocument(Fixtures.xml(row.fixture)).get
        val (sender, receiver) = Fixtures.routing(doc)
        post("message", Fixtures.inboundEnvelope(doc, sender, receiver, row.code)).status shouldBe StatusCodes.NoContent
        val published = publisher.expectMessageType[MqttPublish]
        published.mails.map(n => (n.protocol, n.message.messageCode.toString)) shouldBe List((process(row.code), row.code))
      }
    }

    "answer 500 and publish an error for a body that is not an envelope (pinned)" in {
      post("message", "<foo/>").status shouldBe StatusCodes.InternalServerError
      publisher.expectMessageType[MqttPublishError].tenant shouldBe "NotSpecified"
    }

    "reject malformed XML without publishing (pinned)" in {
      post("message", "<not xml").status.intValue should (be >= 400 and be < 600)
      publisher.expectNoMessage(300.millis)
    }

    "answer 500 for an element outside the envelope's choice (v3 EDASendError, pinned)" in {
      val doc = Fixtures.xml("v3/eda-xml/in/EDASendError.xml")
      post("message", Fixtures.inboundEnvelope(doc, "AT003000", "RC100401", "ERROR")).status shouldBe StatusCodes.InternalServerError
      publisher.expectMessageType[MqttPublishError]
    }

    // 261005-ca11: a document the envelope admits but the parser does not map (CPRequest) ends in a 500
    // and no notification; Ponton retries it forever. Expected: accepted, the error published.
    "accept an unmapped document and publish an error [261005-ca11]" in knownError("261005-ca11") {
      val doc = ProtocolCatalog.outbound.find(_.code == "ANFORDERUNG_ECP").get.message
      val xml = at.energydash.domain.eda.MessageHelper.getEdaMessageByType(doc).get.toXML.asInstanceOf[scala.xml.Elem]
      post("message", Fixtures.inboundEnvelope(xml, "AT003000", "RC100401", "ANFORDERUNG_ECP")).status.isSuccess shouldBe true
      publisher.receiveMessage(1.second) match {
        case MqttPublish(n) => n.head.protocol shouldBe "error"
        case _: MqttPublishError => succeed
        case other => fail(s"unexpected $other")
      }
    }

    // 261005-ca10: the topic tenant comes from the document's routing receiver, never compared with the envelope.
    "reject a document whose receiver differs from the envelope's [261005-ca10]" in knownError("261005-ca10") {
      val doc = Fixtures.xml("v3/eda-xml/in/CMNotification-01.12-ANTWORT_ECON-99.xml")
      val (sender, _) = Fixtures.routing(doc)
      try post("message", Fixtures.inboundEnvelope(doc, sender, "RC999999", "ANTWORT_ECON")).status.isSuccess shouldBe false
      finally scala.util.Try(publisher.receiveMessage(1.second)) // today the document is published under its own receiver
    }
  }

  "POST /pontonxp/notification and /status" should {
    "answer 200 without publishing" in {
      post("notification", "<Notification/>").status shouldBe StatusCodes.OK
      post("status", "<Status/>").status shouldBe StatusCodes.OK
      publisher.expectNoMessage(300.millis)
    }
    "accept a 2 MB body (no size limit, 261005-ca12 — recorded)" in {
      post("notification", "<a>" + ("x" * (2 * 1024 * 1024)) + "</a>").status shouldBe StatusCodes.OK
    }
  }

  "Other requests" should {
    "be refused" in {
      get("message").status shouldBe StatusCodes.MethodNotAllowed
      post("nothing-here", "<a/>").status shouldBe StatusCodes.NotFound
    }
  }
}
