package at.energydash.interfaces

import at.energydash.actors.MqttPublisher.{MqttCommand, MqttPublish}
import at.energydash.actors.http.ServiceRoute
import at.energydash.service.FileService
import at.energydash.testsupport.Fixtures
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.stream.Materializer
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Files
import scala.concurrent.Await
import scala.concurrent.duration._

/** `POST /admin/upload`: EDA files posted by hand are parsed and published like inbound messages. */
class AdminUploadSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  private val publisher = createTestProbe[MqttCommand]()
  private val route = new ServiceRoute(FileService(system, publisher.ref)(Materializer(system)))(system).adminRoutes
  private val binding = Await.result(Http()(system).newServerAt("127.0.0.1", 0).bind(route), 10.seconds)
  private val url = s"http://127.0.0.1:${binding.localAddress.getPort}/admin/upload"

  override def afterAll(): Unit = { Await.ready(binding.unbind(), 10.seconds); super.afterAll() }

  private def upload(name: String, body: String, ecId: Option[String] = None): StatusCode = {
    val form = Multipart.FormData(Multipart.FormData.BodyPart.Strict(name,
      HttpEntity(ContentTypes.`text/xml(UTF-8)`, body), Map("filename" -> s"$name.xml")))
    val entity = Await.result(Marshal(form), 5.seconds)
    val req = HttpRequest(HttpMethods.POST, url, ecId.map(e => RawHeader("ecId", e)).toList, entity)
    val res = Await.result(Http()(system).singleRequest(req), 10.seconds)
    res.discardEntityBytes()(Materializer(system))
    res.status
  }
  private def Marshal(form: Multipart.FormData) =
    org.apache.pekko.http.scaladsl.marshalling.Marshal(form).to[RequestEntity](implicitly, system.executionContext)

  private val antwort = Fixtures.text("v3/eda-xml/in/CMNotification-01.12-ANTWORT_ECON-99.xml")

  "POST /admin/upload" should {
    "publish the document under the part's protocol and with the ecId header" in {
      upload("ANTWORT_ECON_01.10", antwort, Some("AT00300000000RC100401000000000001")) shouldBe StatusCodes.Created
      val n = publisher.expectMessageType[MqttPublish].mails.head
      n.protocol shouldBe "ANTWORT_ECON"
      n.message.messageCode.toString shouldBe "ANTWORT_ECON"
      n.message.ecId shouldBe Some("AT00300000000RC100401000000000001")
    }
    "publish a part with an unparsable name under protocol ERROR (pinned: the caught MatchError)" in {
      upload("not a process!", antwort) shouldBe StatusCodes.Created
      publisher.expectMessageType[MqttPublish].mails.head.protocol shouldBe "ERROR"
    }
    "fail for a body that is not XML (pinned)" in {
      upload("ANTWORT_ECON_01.10", "not xml").intValue should be >= 500
      publisher.expectNoMessage(300.millis)
    }
    "not resolve an external entity (XXE)" in {
      val secret = Files.createTempFile("eda-xxe", ".txt")
      Files.writeString(secret, "XXE-SECRET-MARKER")
      val doc = s"""<?xml version="1.0"?><!DOCTYPE x [<!ENTITY s SYSTEM "${secret.toUri}">]>""" +
        antwort.replaceFirst("<\\?xml[^>]*\\?>", "").replace("ANTWORT_ECON</", "ANTWORT_ECON&s;</")
      upload("ANTWORT_ECON_01.10", doc)
      // rejected (no message) or published without the file's content — never the content
      scala.util.Try(publisher.receiveMessage(1.second)).toOption.foreach(m => m.toString should not include "XXE-SECRET-MARKER")
    }
  }
}
