package at.energydash.logging

import ch.qos.logback.classic.PatternLayout
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

  def line(event: ILoggingEvent, serviceName: String, serviceVersion: String, deploymentEnvironment: Option[String] = None): String = {
    val fields = scala.collection.mutable.LinkedHashMap.empty[String, Any]
    Option(event.getMDCPropertyMap).foreach(_.asScala.foreach { case (k, v) =>
      if (LogFields.Allowed(k) && v != null) fields(k) = v
    })
    Option(event.getKeyValuePairs).foreach(_.asScala.foreach { kv =>
      if (LogFields.Allowed(kv.key) && kv.value != null) fields(kv.key) = kv.value
    })

    val out = new StringWriter(256)
    val g = factory.createGenerator(out)
    g.writeStartObject()
    g.writeStringField("@timestamp", Millis.format(Instant.ofEpochMilli(event.getTimeStamp)))
    g.writeStringField("log.level", event.getLevel.toString.toLowerCase(Locale.ROOT))
    g.writeStringField("log.logger", event.getLoggerName)
    g.writeStringField("service.name", serviceName)
    g.writeStringField("service.version", serviceVersion)
    deploymentEnvironment.foreach(g.writeStringField("deployment.environment", _))
    fields.foreach {
      case (k, n: Number) if LogFields.Numeric(k) => g.writeNumberField(k, n.longValue)
      case (k, v) if LogFields.Numeric(k) && v.toString.toLongOption.isDefined => g.writeNumberField(k, v.toString.toLong)
      case (k, v) => g.writeStringField(k, v.toString)
    }
    Option(event.getThrowableProxy).foreach { t =>
      g.writeStringField("error.type", t.getClassName)
      g.writeStringField("error.message", LogText.exceptionText(t.getClassName, t.getMessage))
      g.writeStringField("error.stack_trace", stackTrace(t))
    }
    g.writeStringField("message", safeMessage(event))
    g.writeEndObject()
    g.close()
    out.append('\n').toString
  }

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
 * The encoder of every log line (`logback.xml`): JSON lines ([[EcsJson]]) or, with `format` `text`, a plain line for
 * local runs. An unknown format is reported to logback and written as JSON; `XpAdapter` stops the start before
 * (see [[LogSettings.stopOnInvalid]]).
 */
class LogEncoder extends EncoderBase[ILoggingEvent] {
  private var format = "json"
  private var text: Option[PatternLayout] = None
  private val serviceVersion = LogSettings.serviceVersion(sys.env)
  private val deploymentEnvironment = LogSettings.deploymentEnvironment(sys.env)

  def setFormat(value: String): Unit = format = value

  override def start(): Unit = {
    text = LogSettings.isText(format) match {
      case Right(true) =>
        val layout = new PatternLayout()
        layout.setContext(getContext)
        layout.setPattern(LogEncoder.TextPattern)
        layout.start()
        Some(layout)
      case Right(false) => None
      case Left(error) =>
        addError(error)
        None
    }
    super.start()
  }

  override def headerBytes(): Array[Byte] = null
  override def footerBytes(): Array[Byte] = null

  override def encode(event: ILoggingEvent): Array[Byte] =
    text.fold(EcsJson.line(event, LogSettings.ServiceName, serviceVersion, deploymentEnvironment)) { layout =>
      // only the message is masked: the fields behind it stay on the line
      val line = layout.doLayout(event)
      val message = Option(event.getFormattedMessage).filter(_.nonEmpty)
      message.fold(line)(line.replace(_, EcsJson.safeMessage(event))) + Option(event.getThrowableProxy).fold("")(EcsJson.stackTrace)
    }.getBytes(StandardCharsets.UTF_8)
}

object LogEncoder {
  /** Local runs: time, level, logger, message, request/tenant/conversation and the key-value pairs. */
  val TextPattern: String =
    "%date{ISO8601} %-5level %logger{0} - %msg request.id=%X{request.id:-} tenant.id=%X{tenant.id:-} " +
      "eda.conversation_id=%X{eda.conversation_id:-} %kvp%n%nopex"
}
