package at.energydash.interfaces

import at.energydash.actors.MqttPublisher.{MqttCommand, MqttPublish}
import at.energydash.actors.SupervisorActor
import at.energydash.actors.http.{AccessLog, BodySizeLimit, HealthRoute, PontonRoute, ServiceRoute}
import at.energydash.logging.{EcsJson, LogFields}
import at.energydash.service.FileService
import at.energydash.testsupport.{Fixtures, LogCapture}
import ch.qos.logback.classic.spi.ILoggingEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.{RawHeader, `User-Agent`}
import org.apache.pekko.stream.Materializer
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters._

/** The access line of port 6090, through the routes the supervisor binds (SupervisorActor.httpRoutes). */
class AccessLogSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  private val publisher = createTestProbe[MqttCommand]()
  private val maxBytes = BodySizeLimit.maxBytes(system.settings.config)
  private val routes = SupervisorActor.httpRoutes(
    new PontonRoute(publisher.ref, maxBodyBytes = maxBytes)(system),
    new ServiceRoute(FileService(system, publisher.ref)(Materializer(system)), maxBytes)(system),
    new HealthRoute(() => Future.unit)(system))
  private val binding = Await.result(Http()(system).newServerAt("127.0.0.1", 0).bind(routes), 10.seconds)
  private val base = s"http://127.0.0.1:${binding.localAddress.getPort}"

  override def afterAll(): Unit = { Await.ready(binding.unbind(), 10.seconds); super.afterAll() }

  /** Sends the request; returns the response and the access lines it produced. */
  private def send(req: HttpRequest): (HttpResponse, List[ILoggingEvent]) = LogCapture("at.energydash.access") {
    val res = Await.result(Http()(system).singleRequest(req), 10.seconds)
    res.discardEntityBytes()(Materializer(system))
    res
  }

  private def fields(e: ILoggingEvent): Map[String, String] =
    Option(e.getKeyValuePairs).map(_.asScala.map(kv => kv.key -> kv.value.toString).toMap).getOrElse(Map.empty)

  private def xml(body: String) = HttpEntity(ContentTypes.`text/xml(UTF-8)`, body)

  "The access line" should {
    "name route, status, outcome, client, tenant and conversation of an inbound message" in {
      val doc = Fixtures.xml("v3/eda-xml/in/CMNotification-01.12-ANTWORT_ECON-99.xml")
      val (sender, receiver) = Fixtures.routing(doc)
      val (res, events) = send(HttpRequest(HttpMethods.POST, s"$base/pontonxp/message",
        headers = List(RawHeader("X-Request-Id", "abc-123"),
          RawHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")),
        entity = xml(Fixtures.inboundEnvelope(doc, sender, receiver, "ANTWORT_ECON"))))
      publisher.expectMessageType[MqttPublish]
      res.status shouldBe StatusCodes.NoContent
      res.headers.find(_.is("x-request-id")).map(_.value) shouldBe Some("abc-123")
      events should have size 1
      val f = fields(events.head)
      events.head.getFormattedMessage shouldBe "POST /pontonxp/message 204"
      events.head.getLevel.toString shouldBe "INFO"
      f(LogFields.EventAction) shouldBe "http.access"
      f(LogFields.EventOutcome) shouldBe "success"
      f(LogFields.HttpMethod) shouldBe "POST"
      f(LogFields.HttpRoute) shouldBe "/pontonxp/message"
      f(LogFields.HttpStatus) shouldBe "204"
      f(LogFields.EventDuration).toLong should be > 0L
      f(LogFields.RequestId) shouldBe "abc-123"
      f(LogFields.TraceId) shouldBe "4bf92f3577b34da6a3ce929d0e0e4736"
      f(LogFields.SpanId) shouldBe "00f067aa0ba902b7"
      f(LogFields.ClientId) shouldBe "ponton"
      f(LogFields.TenantId) shouldBe receiver
      f(LogFields.ConversationId) should not be empty
    }

    "leave out the tenant when the route does not parse the message, and generate an invalid request id" in {
      val (res, events) = send(HttpRequest(HttpMethods.POST, s"$base/pontonxp/notification",
        headers = List(RawHeader("X-Request-Id", "bad id with spaces"), RawHeader("traceparent", "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")),
        entity = xml("<Notification/>")))
      res.status shouldBe StatusCodes.OK
      val f = fields(events.head)
      f(LogFields.RequestId) should fullyMatch regex "[0-9a-f]{32}"
      res.headers.find(_.is("x-request-id")).map(_.value) shouldBe Some(f(LogFields.RequestId))
      f.keySet should contain noneOf (LogFields.TenantId, LogFields.ConversationId, LogFields.TraceId, LogFields.SpanId)
      f(LogFields.ClientId) shouldBe "ponton"
    }

    "write failures, without route for an unknown path and without client for /admin" in {
      val (notFound, unknown) = send(HttpRequest(HttpMethods.POST, s"$base/pontonxp/nothing-here/123?x=secret", entity = xml("<a/>")))
      notFound.status shouldBe StatusCodes.NotFound
      unknown.head.getFormattedMessage shouldBe "POST - 404"
      fields(unknown.head).keySet should not contain LogFields.HttpRoute
      fields(unknown.head)(LogFields.EventOutcome) shouldBe "failure"

      val (_, upload) = send(HttpRequest(HttpMethods.GET, s"$base/admin/upload"))
      val f = fields(upload.head)
      f(LogFields.HttpRoute) shouldBe "/admin/upload"
      f(LogFields.HttpStatus) shouldBe "405"
      f.keySet should not contain LogFields.ClientId
    }

    "not be written for the probes" in {
      send(HttpRequest(uri = s"$base/healthz"))._2 shouldBe empty
      send(HttpRequest(uri = s"$base/readyz"))._2 shouldBe empty
    }

    // no user tracking (2026-10-29): the JSON line holds only these fields, never IP, user agent, query or body
    "carry no user, IP, user agent or query in its JSON line" in {
      val (_, events) = send(HttpRequest(HttpMethods.POST, s"$base/pontonxp/status?member=max@example.com",
        headers = List(`User-Agent`("secret-agent/1.0"), RawHeader("X-Forwarded-For", "203.0.113.7"),
          RawHeader("Authorization", "Bearer secret-token")),
        entity = xml("<Status>secret-body</Status>")))
      val line = EcsJson.line(events.head, "eegfaktura-eda-xp", "dev")
      val keys = new ObjectMapper().readTree(line).fieldNames().asScala.toSet
      keys shouldBe Set("@timestamp", "log.level", "log.logger", "service.name", "service.version", "message",
        "event.action", "event.outcome", "event.duration", "http.request.method", "http.route",
        "http.response.status_code", "request.id", "client.id")
      Seq("secret", "203.0.113.7", "max@example.com", "member=").foreach(s => line should not include s)
    }
  }

  "AccessLog.traceIds" should {
    "refuse version ff, all-zero ids and malformed headers" in {
      AccessLog.traceIds(Some("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")) shouldBe
        Some("4bf92f3577b34da6a3ce929d0e0e4736" -> "00f067aa0ba902b7")
      AccessLog.traceIds(Some("ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")) shouldBe None
      AccessLog.traceIds(Some("00-00000000000000000000000000000000-00f067aa0ba902b7-01")) shouldBe None
      AccessLog.traceIds(Some("00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01")) shouldBe None
      AccessLog.traceIds(Some("00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01")) shouldBe None
      AccessLog.traceIds(None) shouldBe None
    }
    "keep a valid request id and replace an invalid one" in {
      AccessLog.requestId(Some("a.B_c:1-2")) shouldBe "a.B_c:1-2"
      AccessLog.requestId(Some("x" * 129)) should fullyMatch regex "[0-9a-f]{32}"
      AccessLog.requestId(Some("")) should fullyMatch regex "[0-9a-f]{32}"
    }
    "map statuses to outcomes" in {
      Seq(200, 302, 401, 403, 404, 500).map(AccessLog.outcome) shouldBe Seq("success", "success", "refused", "refused", "failure", "failure")
    }
  }
}
