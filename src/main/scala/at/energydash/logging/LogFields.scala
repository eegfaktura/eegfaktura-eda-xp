package at.energydash.logging

import org.slf4j.MDC

/**
 * The field names (ECS) a log line may carry besides the fixed ones of [[EcsJson]]. They come from the MDC or from
 * the key-value pairs of an SLF4J call; any other MDC entry or key is not written (never-log rule).
 */
object LogFields {
  val EventAction = "event.action"
  val EventOutcome = "event.outcome"
  val EventDuration = "event.duration"
  val HttpMethod = "http.request.method"
  val HttpRoute = "http.route"
  val HttpStatus = "http.response.status_code"
  val RequestId = "request.id"
  val TraceId = "trace.id"
  val SpanId = "span.id"
  val TenantId = "tenant.id"
  val ClientId = "client.id"
  /** The EDA ConversationId of the message a line handles. */
  val ConversationId = "eda.conversation_id"

  val LogEffectiveLevel = "log.effective_level"

  val Allowed: Set[String] = Set(LogEffectiveLevel, EventAction, EventOutcome, EventDuration, HttpMethod, HttpRoute, HttpStatus,
    RequestId, TraceId, SpanId, TenantId, ClientId, ConversationId)

  /** Written as JSON numbers. */
  val Numeric: Set[String] = Set(EventDuration, HttpStatus)

  private val ValidTenant = "[A-Z0-9][A-Z0-9_-]{0,63}".r

  /**
   * The `tenant.id` of a value that comes from outside (the rule of all services): upper case, and none when it does
   * not have the shape of a tenant.
   */
  def tenantId(value: String): Option[String] =
    Option(value).map(_.toUpperCase(java.util.Locale.ROOT)).filter(ValidTenant.matches)

  /** The fields of this thread's MDC, for [[withMdc]] in code that goes on on another thread (a stream stage). */
  def current(): Seq[(String, Option[String])] = Allowed.toSeq.map(k => k -> Option(MDC.get(k)))

  /**
   * Runs `body` with the given fields in the MDC (only those with a non-empty value) and restores the previous
   * values afterwards. For synchronous code: the MDC belongs to the thread.
   */
  def withMdc[T](fields: (String, Option[String])*)(body: => T): T = {
    val set = fields.collect { case (k, Some(v)) if v.nonEmpty => k -> v }
    val previous = set.map { case (k, _) => k -> Option(MDC.get(k)) }
    set.foreach { case (k, v) => MDC.put(k, v) }
    try body
    finally previous.foreach {
      case (k, Some(v)) => MDC.put(k, v)
      case (k, None) => MDC.remove(k)
    }
  }
}
