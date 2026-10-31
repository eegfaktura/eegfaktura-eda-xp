package at.energydash.actors.http

import at.energydash.logging.LogFields._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{AttributeKey, HttpRequest, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.slf4j.event.Level
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One access line per HTTP request on port 6090 (logging concept point 6, `event.action=http.access`), written when
 * the response is ready: method, route template, status, outcome, duration (ns), `request.id`, `trace.id`/`span.id`
 * of a valid `traceparent`, `client.id` and `tenant.id`. There is no token on this port: `client.id` is the fixed
 * caller of the route (`ponton` for `/pontonxp/…`), `tenant.id` and `eda.conversation_id` are set by the route once
 * it has parsed the message ([[AccessLog.Context]]). No user, IP, user agent, query or body (no user tracking,
 * 2026-10-29). The probe paths are not logged. On a request timeout the line has the 503 the caller gets, even if the
 * route finishes later. A connection the client drops before the response gets no line.
 */
object AccessLog {
  val RequestIdHeader = "X-Request-Id"
  val ProbePaths: Set[String] = Set("/healthz", "/readyz")

  // pekko's own text for a request timeout
  private val TimeoutText =
    "The server was not able to produce a timely response to your request.\r\nPlease try again in a short while!"

  private val logger: Logger = LoggerFactory.getLogger("at.energydash.access")
  private val ValidRequestId = "[A-Za-z0-9._:-]{1,128}".r
  private val TraceParent = "([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})".r

  /** Per request: what the route learns while it handles the message. */
  final class Context(val requestId: String, val traceParent: Option[String]) {
    @volatile var tenant: Option[String] = None
    @volatile var conversationId: Option[String] = None
    private[AccessLog] val logged = new AtomicBoolean(false)
  }

  val ContextKey: AttributeKey[Context] = AttributeKey[Context]("eda-xp-access-context")

  /** The incoming id when it is 1–128 characters of `[A-Za-z0-9._:-]`, else a new one (uuid4, 32 hex). */
  def requestId(incoming: Option[String]): String =
    incoming.filter(ValidRequestId.matches).getOrElse(UUID.randomUUID().toString.replace("-", ""))

  /** trace id and parent (span) id of a valid W3C traceparent: not version ff, no all-zero id. */
  def traceIds(traceParent: Option[String]): Option[(String, String)] = traceParent.collect {
    case TraceParent(version, trace, span, _) if version != "ff" && trace != "0" * 32 && span != "0" * 16 => (trace, span)
  }

  def outcome(status: Int): String =
    if (status == 401 || status == 403) "refused" else if (status >= 400) "failure" else "success"

  /** The fixed caller of a route, if it has one. */
  def clientOf(route: String): Option[String] = if (route.startsWith("/pontonxp/")) Some("ponton") else None

  private def header(request: HttpRequest, name: String): Option[String] =
    request.headers.find(_.is(name.toLowerCase)).map(_.value)

  /**
   * Wraps the routes of the server. `templates` are the paths of the routes (they have no path parameters, so the
   * path is the template); a request to any other path is logged without `http.route`.
   */
  def apply(templates: Set[String])(inner: Route): Route = extractRequest { request =>
    val path = request.uri.path.toString
    if (ProbePaths(path)) inner
    else {
      val started = System.nanoTime()
      val traceParent = header(request, "traceparent").filter(tp => traceIds(Some(tp)).isDefined)
      val context = new Context(requestId(header(request, RequestIdHeader)), traceParent)
      val route = Some(path).filter(templates)
      val timeoutResponse = (_: HttpRequest) => {
        log(request.method.value, route, StatusCodes.ServiceUnavailable.intValue, System.nanoTime() - started, context)
        HttpResponse(StatusCodes.ServiceUnavailable, entity = TimeoutText).addHeader(RawHeader(RequestIdHeader, context.requestId))
      }
      mapRequest(_.addAttribute(ContextKey, context)) {
        withRequestTimeoutResponse(timeoutResponse) {
          respondWithHeader(RawHeader(RequestIdHeader, context.requestId)) {
            mapResponse { response =>
              log(request.method.value, route, response.status.intValue, System.nanoTime() - started, context)
              response
            }(Route.seal(inner))
          }
        }
      }
    }
  }

  private def log(method: String, route: Option[String], status: Int, durationNanos: Long, context: Context): Unit =
    if (context.logged.compareAndSet(false, true)) write(method, route, status, durationNanos, context)

  private def write(method: String, route: Option[String], status: Int, durationNanos: Long, context: Context): Unit = {
    val result = outcome(status)
    var line = logger.atLevel(if (result == "refused") Level.WARN else Level.INFO)
      .addKeyValue(EventAction, "http.access")
      .addKeyValue(EventOutcome, result)
      .addKeyValue(EventDuration, java.lang.Long.valueOf(durationNanos))
      .addKeyValue(HttpMethod, method)
      .addKeyValue(HttpStatus, Integer.valueOf(status))
      .addKeyValue(RequestId, context.requestId)
    route.foreach(r => line = line.addKeyValue(HttpRoute, r))
    route.flatMap(clientOf).foreach(c => line = line.addKeyValue(ClientId, c))
    traceIds(context.traceParent).foreach { case (trace, span) =>
      line = line.addKeyValue(TraceId, trace).addKeyValue(SpanId, span)
    }
    context.tenant.foreach(t => line = line.addKeyValue(TenantId, t))
    context.conversationId.foreach(c => line = line.addKeyValue(ConversationId, c))
    line.setMessage(s"$method ${route.getOrElse("-")} $status").log()
  }
}
