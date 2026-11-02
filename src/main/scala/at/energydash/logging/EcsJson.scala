package at.energydash.logging

import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.{ILoggingEvent, ThrowableProxyUtil}
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
 * the [[LogFields.Allowed]] fields from the MDC and the key-value pairs (anything else is dropped),
 * `error.type`/`error.message`/`error.stack_trace` for an attached exception, and `message`.
 */
object EcsJson {
  private val factory = new JsonFactory()
  private val Millis = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  def line(event: ILoggingEvent, serviceName: String, serviceVersion: String): String = {
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
    fields.foreach {
      case (k, n: Number) if LogFields.Numeric(k) => g.writeNumberField(k, n.longValue)
      case (k, v) if LogFields.Numeric(k) && v.toString.toLongOption.isDefined => g.writeNumberField(k, v.toString.toLong)
      case (k, v) => g.writeStringField(k, v.toString)
    }
    Option(event.getThrowableProxy).foreach { t =>
      g.writeStringField("error.type", t.getClassName)
      g.writeStringField("error.message", Option(t.getMessage).getOrElse(""))
      g.writeStringField("error.stack_trace", ThrowableProxyUtil.asString(t))
    }
    g.writeStringField("message", Option(event.getFormattedMessage).getOrElse(""))
    g.writeEndObject()
    g.close()
    out.append('\n').toString
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
    text.fold(EcsJson.line(event, LogSettings.ServiceName, serviceVersion))(_.doLayout(event))
      .getBytes(StandardCharsets.UTF_8)
}

object LogEncoder {
  /** Local runs: time, level, logger, message, request/tenant/conversation and the key-value pairs. */
  val TextPattern: String =
    "%date{ISO8601} %-5level %logger{0} - %msg request.id=%X{request.id:-} tenant.id=%X{tenant.id:-} " +
      "eda.conversation_id=%X{eda.conversation_id:-} %kvp%n"
}
