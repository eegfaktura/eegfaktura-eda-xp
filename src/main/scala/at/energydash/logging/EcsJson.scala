package at.energydash.logging

import ch.qos.logback.classic.spi.{ILoggingEvent, IThrowableProxy, ThrowableProxyUtil}
import ch.qos.logback.core.CoreConstants
import ch.qos.logback.core.encoder.EncoderBase
import com.fasterxml.jackson.core.JsonFactory

import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import java.util.Locale
import scala.jdk.CollectionConverters._

/**
 * One log line as one JSON object with flat ECS names, the same shape as the other eegfaktura services:
 * `@timestamp` (UTC, milliseconds), `log.level` (lower case), `log.logger`, `service.name`, `service.version`,
 * `deployment.environment` when it is set,
 * the [[LogFields.Allowed]] fields from the MDC and the key-value pairs (anything else is dropped),
 * `error.type`/`error.message`/`error.stack_trace` for an attached exception, and `message`. Message and error text
 * pass [[LogText]], so an exception of a library or of pekko cannot bring a document into the log either.
 */
object EcsJson {
  private val factory = new JsonFactory()
  private val Millis = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
  /** Not repeated after the message of a text line: the head has them, the stack starts with type and message. */
  private val TextHead = Set("@timestamp", "log.level", "log.logger", "message", "service.name", "service.version",
    "error.type", "error.message", "error.stack_trace")

  /** The fields of the line, in output order; the JSON and the text line are written from them. */
  def fields(event: ILoggingEvent, serviceName: String, serviceVersion: String, deploymentEnvironment: Option[String] = None): Seq[(String, Any)] = {
    val present: Map[String, Any] =
      Option(event.getMDCPropertyMap).map(_.asScala.toMap).getOrElse(Map.empty[String, String]) ++
        Option(event.getKeyValuePairs).map(_.asScala.map(kv => kv.key -> kv.value).toMap).getOrElse(Map.empty)
    val error = Option(event.getThrowableProxy).toSeq.flatMap { t =>
      Seq("error.type" -> t.getClassName, "error.message" -> LogText.exceptionText(t.getClassName, t.getMessage),
        "error.stack_trace" -> stackTrace(t))
    }
    Seq(
      "@timestamp" -> Millis.format(Instant.ofEpochMilli(event.getTimeStamp)),
      "log.level" -> event.getLevel.toString.toLowerCase(Locale.ROOT),
      "log.logger" -> event.getLoggerName,
      "service.name" -> serviceName,
      "service.version" -> serviceVersion,
    ) ++ deploymentEnvironment.map("deployment.environment" -> _) ++
      LogFields.Allowed.flatMap(k => present.get(k).filter(_ != null).map(k -> _)) ++
      error ++ Seq("message" -> safeMessage(event))
  }

  /** One JSON object and a line break. */
  def line(event: ILoggingEvent, serviceName: String, serviceVersion: String, deploymentEnvironment: Option[String] = None): String = {
    val out = new StringWriter(256)
    val g = factory.createGenerator(out)
    g.writeStartObject()
    fields(event, serviceName, serviceVersion, deploymentEnvironment).foreach {
      case (k, n: Number) if LogFields.Numeric(k) => g.writeNumberField(k, n.longValue)
      case (k, v) if LogFields.Numeric(k) && v.toString.toLongOption.isDefined => g.writeNumberField(k, v.toString.toLong)
      case (k, v) => g.writeStringField(k, v.toString)
    }
    g.writeEndObject()
    g.close()
    out.append('\n').toString
  }

  /** The same fields as a readable line for local runs: time, level, logger, message, `key=value`, the stack. */
  def text(event: ILoggingEvent, serviceName: String, serviceVersion: String, deploymentEnvironment: Option[String] = None): String = {
    val all = fields(event, serviceName, serviceVersion, deploymentEnvironment)
    val f = all.toMap
    val head = f"${f("@timestamp")} ${f("log.level")}%-5s ${f("log.logger")}: ${f("message")}"
    val extra = all.filterNot { case (k, _) => TextHead(k) }.map { case (k, v) => s"$k=${quoted(v.toString)}" }
    (head +: extra).mkString(" ") + "\n" + f.get("error.stack_trace").fold("")(_.toString)
  }

  private def quoted(v: String): String =
    if (v.nonEmpty && !v.exists(c => c == ' ' || c == '"' || c == '=' || c == '\n')) v
    else "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

  /** Causes and suppressed exceptions below this depth are not written; logback has already cut a circular reference. */
  private val MaxDepth = 16

  /**
   * The message of the event without a document or an address. An exception's text is never part of it: a log call
   * attaches the exception instead (`NeverLogSpec` refuses `getMessage` in the text).
   */
  def safeMessage(event: ILoggingEvent): String =
    LogText.withoutEmailAddresses(LogText.errorText(event.getFormattedMessage))

  /**
   * The stack as logback writes it, with the message of the exception and of each cause and suppressed exception
   * through [[LogText.exceptionText]].
   */
  def stackTrace(t: IThrowableProxy): String = {
    val out = new java.lang.StringBuilder(2048)
    appendStack(out, t, "", 1, 0)
    out.toString
  }

  private def appendStack(out: java.lang.StringBuilder, t: IThrowableProxy, prefix: String, indent: Int, depth: Int): Unit =
    if (t != null && depth < MaxDepth) {
      ThrowableProxyUtil.indent(out, indent - 1)
      out.append(prefix)
      val text = s"${t.getClassName}: ${LogText.exceptionText(t.getClassName, t.getMessage)}"
      if (t.isCyclic) out.append("[CIRCULAR REFERENCE: ").append(text).append(']') else out.append(text)
      out.append(CoreConstants.LINE_SEPARATOR)
      ThrowableProxyUtil.subjoinSTEPArray(out, indent, t)
      Option(t.getSuppressed).foreach(_.foreach(s =>
        appendStack(out, s, CoreConstants.SUPPRESSED, indent + ThrowableProxyUtil.SUPPRESSED_EXCEPTION_INDENT, depth + 1)))
      appendStack(out, t.getCause, CoreConstants.CAUSED_BY, indent, depth + 1)
    }
}

/**
 * The encoder of every log line (`logback.xml`): JSON lines ([[EcsJson.line]]) or, with `format` `text`, the same
 * fields as a plain line for local runs ([[EcsJson.text]]). An unknown format is reported to logback and written as
 * JSON; `XpAdapter` stops the start before (see [[LogSettings.stopOnInvalid]]).
 */
class LogEncoder extends EncoderBase[ILoggingEvent] {
  private var format = "json"
  private var text = false
  private val serviceVersion = LogSettings.serviceVersion(sys.env)
  private val deploymentEnvironment = LogSettings.deploymentEnvironment(sys.env)

  def setFormat(value: String): Unit = format = value

  override def start(): Unit = {
    text = LogSettings.isText(format) match {
      case Right(isText) => isText
      case Left(error) =>
        addError(error)
        false
    }
    super.start()
  }

  override def headerBytes(): Array[Byte] = null
  override def footerBytes(): Array[Byte] = null

  override def encode(event: ILoggingEvent): Array[Byte] =
    (if (text) EcsJson.text(event, LogSettings.ServiceName, serviceVersion, deploymentEnvironment)
    else EcsJson.line(event, LogSettings.ServiceName, serviceVersion, deploymentEnvironment)).getBytes(StandardCharsets.UTF_8)
}
