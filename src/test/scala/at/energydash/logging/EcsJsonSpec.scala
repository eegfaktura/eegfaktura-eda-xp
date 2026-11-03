package at.energydash.logging

import ch.qos.logback.classic.spi.{LoggingEvent, ThrowableProxy, ThrowableProxyUtil}
import ch.qos.logback.classic.{Level, LoggerContext}
import com.fasterxml.jackson.databind.ObjectMapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.event.KeyValuePair

import scala.jdk.CollectionConverters._

class EcsJsonSpec extends AnyWordSpec with Matchers {
  private val context = new LoggerContext()
  private val mapper = new ObjectMapper()

  private def event(level: Level, message: String, mdc: Map[String, String] = Map.empty,
                    kv: Seq[(String, AnyRef)] = Nil, error: Option[Throwable] = None): LoggingEvent = {
    val e = new LoggingEvent("x", context.getLogger("at.energydash.Test"), level, message, error.orNull, null)
    e.setTimeStamp(1798629296789L) // 2026-12-30T11:14:56.789Z
    e.setMDCPropertyMap(mdc.asJava)
    kv.foreach { case (k, v) => e.addKeyValuePair(new KeyValuePair(k, v)) }
    e
  }

  private def json(e: LoggingEvent): Map[String, Any] =
    mapper.readValue(EcsJson.line(e, "eegfaktura-eda-xp", "1.2.3"), classOf[java.util.Map[String, Any]]).asScala.toMap

  "EcsJson.line" should {
    "write one JSON object per line with the common fields" in {
      val line = EcsJson.line(event(Level.INFO, "hello \"world\"\nnext"), "eegfaktura-eda-xp", "1.2.3")
      line should endWith("}\n")
      line.count(_ == '\n') shouldBe 1
      val fields = json(event(Level.INFO, "hello"))
      fields("@timestamp") shouldBe "2026-12-30T11:14:56.789Z"
      fields("log.level") shouldBe "info"
      fields("log.logger") shouldBe "at.energydash.Test"
      fields("service.name") shouldBe "eegfaktura-eda-xp"
      fields("service.version") shouldBe "1.2.3"
      fields("message") shouldBe "hello"
    }
    "write every level in lower case" in {
      Seq(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR).map(l => json(event(l, "m"))("log.level")) shouldBe
        Seq("trace", "debug", "info", "warn", "error")
    }
    "take the allowed fields from the MDC and the key-value pairs and drop any other" in {
      val fields = json(event(Level.INFO, "m",
        mdc = Map("tenant.id" -> "RC100001", "eda.conversation_id" -> "C1", "pekkoSource" -> "x", "user.email" -> "a@b.c"),
        kv = Seq("http.route" -> "/pontonxp/message", "http.response.status_code" -> Integer.valueOf(204),
          "event.duration" -> java.lang.Long.valueOf(1234567L), "client.ip" -> "10.0.0.1", "user_agent.original" -> "curl")))
      fields("tenant.id") shouldBe "RC100001"
      fields("eda.conversation_id") shouldBe "C1"
      fields("http.route") shouldBe "/pontonxp/message"
      fields("http.response.status_code") shouldBe 204
      fields("event.duration") shouldBe 1234567
      fields.keySet shouldBe Set("@timestamp", "log.level", "log.logger", "service.name", "service.version", "message",
        "tenant.id", "eda.conversation_id", "http.route", "http.response.status_code", "event.duration")
    }
    "write an exception as error fields in the same record" in {
      val e = event(Level.ERROR, "failed", error = Some(new IllegalStateException("boom")))
      val fields = json(e)
      fields("error.type") shouldBe "java.lang.IllegalStateException"
      fields("error.message") shouldBe "boom"
      fields("error.stack_trace").toString should include("EcsJsonSpec")
    }
  }

  "deployment.environment (dev#45 L14)" should {
    "be on the line when it is set" in {
      val line = EcsJson.line(event(Level.INFO, "m"), "eegfaktura-eda-xp", "1.2.3", Some("pilot"))
      mapper.readTree(line).get("deployment.environment").asText shouldBe "pilot"
    }
    "be left out when it is not set" in {
      json(event(Level.INFO, "m")).keySet should not contain "deployment.environment"
    }
  }

  "LogFields.tenantId" should {
    "pass a tenant, trimmed and in upper case, and a GEA tenant" in {
      LogFields.tenantId("RC100401") shouldBe Some("RC100401")
      LogFields.tenantId("rc100401") shouldBe Some("RC100401")
      LogFields.tenantId(" rc100401\n") shouldBe Some("RC100401")
      LogFields.tenantId("GC123456-001") shouldBe Some("GC123456-001")
      LogFields.tenantId("A" * 64) shouldBe Some("A" * 64)
    }
    "refuse anything else" in {
      Seq("", " ", "-RC1", "_RC1", "RC 1", "max@example.com", "RC1\nRC2", "A" * 65, null).foreach { value =>
        withClue(String.valueOf(value))(LogFields.tenantId(value) shouldBe None)
      }
    }
    // dev#45 Q5: one place for the rule, whatever writes the field
    "be applied by the encoder to the MDC and the key-value pairs" in {
      json(event(Level.INFO, "m", mdc = Map("tenant.id" -> "rc100401")))("tenant.id") shouldBe "RC100401"
      json(event(Level.INFO, "m", kv = Seq("tenant.id" -> "gc123456-001")))("tenant.id") shouldBe "GC123456-001"
      json(event(Level.INFO, "m", mdc = Map("tenant.id" -> "max@example.com"))).keySet should not contain "tenant.id"
      json(event(Level.INFO, "m", kv = Seq("tenant.id" -> "RC 1"))).keySet should not contain "tenant.id"
    }
  }

  "A logged exception" should {
    val document = "<v320:Message><Meter>AT0030000000000000000000000526178</Meter></v320:Message>"
    val failure = s"""Error while parsing $document: parser error "bad" while parsing /{ns}Message"""

    "not bring a document or an address into message, error.message or the stack, also as a cause" in {
      val fields = json(event(Level.ERROR, s"Recover: $failure",
        error = Some(new RuntimeException("mail to max@example.com failed", new IllegalStateException(failure)))))
      fields("message") shouldBe """Recover: Error while parsing the document: parser error "bad" while parsing /{ns}Message"""
      fields("error.message") shouldBe "mail to <address> failed"
      val stack = fields("error.stack_trace").toString
      stack should include("Caused by: java.lang.IllegalStateException: Error while parsing the document: parser error")
      stack should include("EcsJsonSpec")
      Seq("AT003", "<v320", "max@example.com").foreach(s => fields.values.mkString should not include s)
    }
    // dev#45 Q1: the text of an exception is no longer masked inside message; NeverLogSpec refuses it there
    "not leave an address in message" in {
      val address = new RuntimeException("550 no mailbox max@example.com")
      json(event(Level.ERROR, s"Recover: ${address.getMessage}", error = Some(address)))("message") shouldBe
        "Recover: 550 no mailbox <address>"
      json(event(Level.ERROR, "SMTP said max@example.com"))("message") shouldBe "SMTP said <address>"
    }
    "write the stack as logback does, with causes and suppressed exceptions" in {
      val error = new RuntimeException("outer", new IllegalStateException("inner"))
      error.addSuppressed(new IllegalArgumentException("aside", new RuntimeException("below")))
      val proxy = new ThrowableProxy(error)
      EcsJson.stackTrace(proxy) shouldBe ThrowableProxyUtil.asString(proxy)
    }
    "write a missing message and a circular reference as logback does" in {
      val proxy = new ThrowableProxy(new IllegalStateException(null: String, new RuntimeException(null: String)))
      EcsJson.stackTrace(proxy) shouldBe ThrowableProxyUtil.asString(proxy)
      EcsJson.stackTrace(proxy).linesIterator.next() shouldBe "java.lang.IllegalStateException: null"
      val outer = new RuntimeException("outer")
      val inner = new IllegalStateException("inner", outer)
      outer.initCause(inner)
      val circular = new ThrowableProxy(outer)
      EcsJson.stackTrace(circular) shouldBe ThrowableProxyUtil.asString(circular)
      EcsJson.stackTrace(circular) should include("[CIRCULAR REFERENCE: java.lang.RuntimeException: outer]")
    }
    "say how many causes it leaves out below 16 levels" in {
      val error = (1 to 19).foldLeft(new RuntimeException("root"): Throwable)((cause, i) => new RuntimeException(s"level $i", cause))
      val lines = EcsJson.stackTrace(new ThrowableProxy(error)).linesIterator.toList
      lines.count(_.startsWith("Caused by: ")) shouldBe 15
      lines.last shouldBe "... 4 more causes omitted"
      lines.mkString should not include "java.lang.RuntimeException: root"
    }
    "mask each message of the stack on its own line" in {
      val error = new RuntimeException("mail to max@example.com failed", new MatchError(document))
      val lines = EcsJson.stackTrace(new ThrowableProxy(error)).linesIterator.toList
      lines.head shouldBe "java.lang.RuntimeException: mail to <address> failed"
      lines should contain("Caused by: scala.MatchError: (content not logged)")
    }
    "keep the operator text of a message that was not handed to MQTT" in {
      // PontonRoute's text; the reason is on the exception (dev#45 Q1)
      val text = "Inbound message (conversation RC1, message code ANTWORT_ECON) not handed to MQTT after 12 ms; Ponton will redeliver"
      val fields = json(event(Level.ERROR, text, error = Some(new IllegalStateException("MQTT client not connected"))))
      fields("message") shouldBe text
      fields("error.message") shouldBe "MQTT client not connected"
    }
    "not bring a document or an address in through a suppressed exception" in {
      val error = new RuntimeException("failed")
      val suppressed = new IllegalStateException(failure, new RuntimeException("mail to max@example.com failed"))
      error.addSuppressed(suppressed)
      val stack = json(event(Level.ERROR, "failed", error = Some(error)))("error.stack_trace").toString
      stack should include("Suppressed: java.lang.IllegalStateException: Error while parsing the document: parser error")
      stack should include("mail to <address> failed")
      Seq("AT003", "max@example.com").foreach(s => stack should not include s)
    }
    "mask a message that holds the message of another exception of the chain" in {
      val error = new RuntimeException("max@example.com", new MatchError("<doc>max@example.com AT003</doc>"))
      json(event(Level.ERROR, "failed", error = Some(error)))("error.stack_trace").toString should not include "AT003"
    }
    "not write the value of a MatchError" in {
      val fields = json(event(Level.ERROR, "failed", error = Some(new MatchError(document))))
      fields("error.type") shouldBe "scala.MatchError"
      fields.values.mkString should not include "AT003"
    }
    "keep the end of a text line whose document has no marker" in {
      val enc = new LogEncoder()
      enc.setContext(context)
      enc.setFormat("text")
      enc.start()
      val line = new String(enc.encode(event(Level.ERROR, s"Failed: Error while parsing $document", mdc = Map("tenant.id" -> "RC100001"))), "UTF-8")
      line should not include "AT003"
      line should endWith("at.energydash.Test: Failed: Error while parsing the document (content not logged) tenant.id=RC100001\n")
    }
    "not bring a document into the text format, with the stack once" in {
      val enc = new LogEncoder()
      enc.setContext(context)
      enc.setFormat("text")
      enc.start()
      val line = new String(enc.encode(event(Level.ERROR, "failed", error = Some(new IllegalStateException(failure)))), "UTF-8")
      line should not include "AT003"
      "IllegalStateException".r.findAllIn(line).size shouldBe 1
      line should include("EcsJsonSpec")
    }
  }

  "LogEncoder" should {
    def encoder(format: String) = {
      val enc = new LogEncoder()
      enc.setContext(context)
      enc.setFormat(format)
      enc.start()
      enc
    }
    "write JSON for json" in {
      new String(encoder("json").encode(event(Level.INFO, "m")), "UTF-8") should startWith("{\"@timestamp\"")
    }
    // dev#45 Q3: the text line is written from the fields of the JSON line
    "write a plain line with the fields of the JSON line for text" in {
      val e = event(Level.WARN, "m", mdc = Map("tenant.id" -> "RC100001", "pekkoSource" -> "x"),
        kv = Seq("http.route" -> "/pontonxp/message", "event.duration" -> java.lang.Long.valueOf(12L), "client.ip" -> "10.0.0.1"))
      val line = new String(encoder("TEXT").encode(e), "UTF-8")
      line shouldBe "2026-12-30T11:14:56.789Z warn  at.energydash.Test: m event.duration=12 http.route=/pontonxp/message " +
        "tenant.id=RC100001\n"
    }
    "quote a text value with a space, a quote or a line break" in {
      val line = new String(encoder("text").encode(event(Level.INFO, "m", kv = Seq("event.action" -> "a \"b\"\nc"))), "UTF-8")
      line should endWith(""": m event.action="a \"b\"\nc"""" + "\n")
    }
  }

  "TenantConfig" should {
    "keep user and password out of its text" in {
      val config = at.energydash.domain.dao.TenantConfig("RC100001", "MAIL", None, None, None, None, None,
        Some("mailbox-user"), Some("mailbox-secret"), None, None, active = true)
      config.toString shouldBe "TenantConfig(RC100001, MAIL, active=true)"
    }
  }
}
