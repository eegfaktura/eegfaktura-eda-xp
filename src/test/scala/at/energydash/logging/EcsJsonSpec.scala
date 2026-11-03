package at.energydash.logging

import ch.qos.logback.classic.spi.{LoggingEvent, ThrowableProxy, ThrowableProxyUtil}
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.status.{Status, StatusUtil}
import com.fasterxml.jackson.databind.ObjectMapper
import at.energydash.testsupport.LogCapture
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
    "come from DEPLOYMENT_ENVIRONMENT, an empty value counting as unset" in {
      LogSettings.deploymentEnvironment(Map("DEPLOYMENT_ENVIRONMENT" -> " prod ")) shouldBe Some("prod")
      LogSettings.deploymentEnvironment(Map("DEPLOYMENT_ENVIRONMENT" -> " ")) shouldBe None
      LogSettings.deploymentEnvironment(Map.empty) shouldBe None
    }
  }

  "The start-up line (dev#45 L14)" should {
    "name version and effective log levels, and nothing else" in {
      val (_, events) = LogCapture(LogSettings.StartLogger)(LogSettings.logStart())
      events should have size 1
      events.head.getFormattedMessage should fullyMatch regex
        "Service start: version \\S+, log level (trace|debug|info|warn|error), slick log level (trace|debug|info|warn|error)"
      events.head.getKeyValuePairs.asScala.map(kv => kv.key -> kv.value) shouldBe Seq("event.action" -> "service.start", "log.effective_level" -> "info")
    }
    "be written at any LOG_LEVEL" in {
      System.setProperty("LOG_LEVEL", "error")
      try {
        val ctx = new LoggerContext()
        val configurator = new JoranConfigurator()
        configurator.setContext(ctx)
        configurator.doConfigure(getClass.getResource("/logback.xml"))
        ctx.getLogger(LogSettings.StartLogger).isInfoEnabled shouldBe true
        ctx.getLogger("at.energydash.Other").isInfoEnabled shouldBe false
        ctx.stop()
      } finally System.clearProperty("LOG_LEVEL")
    }
  }

  "LogFields.tenantId" should {
    "pass a tenant, in upper case, and a GEA tenant" in {
      LogFields.tenantId("RC100401") shouldBe Some("RC100401")
      LogFields.tenantId("rc100401") shouldBe Some("RC100401")
      LogFields.tenantId("GC123456-001") shouldBe Some("GC123456-001")
      LogFields.tenantId("A" * 64) shouldBe Some("A" * 64)
    }
    "refuse anything else" in {
      Seq("", " RC100401", "-RC1", "_RC1", "RC 1", "max@example.com", "RC1\n", "A" * 65, null).foreach { value =>
        withClue(String.valueOf(value))(LogFields.tenantId(value) shouldBe None)
      }
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
    "mask each message of the stack on its own line" in {
      val error = new RuntimeException("mail to max@example.com failed", new MatchError(document))
      val lines = EcsJson.stackTrace(new ThrowableProxy(error)).linesIterator.toList
      lines.head shouldBe "java.lang.RuntimeException: mail to <address> failed"
      lines should contain("Caused by: scala.MatchError: (content not logged)")
    }
    "keep the operator text of a message that was not handed to MQTT" in {
      val text = "Inbound message (conversation RC1, message code ANTWORT_ECON) not handed to MQTT after 12 ms: " +
        "java.lang.IllegalStateException: MQTT client not connected; Ponton will redeliver"
      json(event(Level.ERROR, text, error = Some(new IllegalStateException("MQTT client not connected"))))("message") shouldBe text
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
    "leave a null in the line alone for a MatchError of null" in {
      val line = json(event(Level.ERROR, "message code null not handed to MQTT", error = Some(new MatchError(null))))
      line("message").toString should include("message code null not handed to MQTT")
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
      line should include("Failed: Error while parsing the document (content not logged) request.id= tenant.id=RC100001 eda.conversation_id=")
      line should endWith("\n")
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
    "write a plain line with request, tenant and conversation for text" in {
      val line = new String(encoder("TEXT").encode(event(Level.WARN, "m", mdc = Map("tenant.id" -> "RC100001"))), "UTF-8")
      line should include("WARN  Test - m request.id= tenant.id=RC100001 eda.conversation_id=")
      line should not startWith "{"
    }
  }

  "TenantConfig" should {
    "keep user and password out of its text" in {
      val config = at.energydash.domain.dao.TenantConfig("RC100001", "MAIL", None, None, None, None, None,
        Some("mailbox-user"), Some("mailbox-secret"), None, None, active = true)
      config.toString shouldBe "TenantConfig(RC100001, MAIL, active=true)"
    }
  }

  "java.util.logging" should {
    "come out through SLF4J as a JSON line, with the exception (dev#45 L13)" in {
      LogSettings.bridgeJavaUtilLogging()
      LogSettings.bridgeJavaUtilLogging() // once only, or each line would come twice
      val jul = java.util.logging.Logger.getLogger("io.grpc.Test")
      val (_, events) = LogCapture("io.grpc.Test") {
        jul.log(java.util.logging.Level.WARNING, "channel broken", new IllegalStateException("boom"))
      }
      events should have size 1
      val fields = mapper.readValue(EcsJson.line(events.head, "eegfaktura-eda-xp", "dev"), classOf[java.util.Map[String, Any]]).asScala
      fields("log.logger") shouldBe "io.grpc.Test"
      fields("log.level") shouldBe "warn"
      fields("message") shouldBe "channel broken"
      fields("error.type") shouldBe "java.lang.IllegalStateException"
    }
  }

  "LogSettings" should {
    "accept the five levels and two formats in any case, and unset or empty variables" in {
      LogSettings.check(Map("LOG_LEVEL" -> "TRACE", "SLICK_LOG_LEVEL" -> "warn", "LOG_FORMAT" -> "Json")) shouldBe Nil
      LogSettings.check(Map.empty) shouldBe Nil
      LogSettings.check(Map("LOG_LEVEL" -> "", "SLICK_LOG_LEVEL" -> " ", "LOG_FORMAT" -> "")) shouldBe Nil
    }
    "refuse an unknown value" in {
      LogSettings.check(Map("LOG_LEVEL" -> "verbose")) should have size 1
      LogSettings.check(Map("SLICK_LOG_LEVEL" -> "warning")) should have size 1
      LogSettings.check(Map("LOG_FORMAT" -> "ecs")).head should include("LOG_FORMAT")
    }
    "give the default for an empty variable, not for an unset or filled one" in {
      LogSettings.defaultsForEmpty(Map("LOG_LEVEL" -> "", "SLICK_LOG_LEVEL" -> " ", "LOG_FORMAT" -> "")) shouldBe
        Map("LOG_LEVEL" -> "info", "SLICK_LOG_LEVEL" -> "info", "LOG_FORMAT" -> "json")
      LogSettings.defaultsForEmpty(Map("LOG_LEVEL" -> "warn")) shouldBe Map.empty
    }
    "start with the settings of this environment" in {
      noException should be thrownBy new LogStartup {}
    }
    "take SERVICE_VERSION, else dev" in {
      LogSettings.serviceVersion(Map("SERVICE_VERSION" -> "0.2.22")) shouldBe "0.2.22"
      LogSettings.serviceVersion(Map("SERVICE_VERSION" -> " ")) shouldBe "dev"
      LogSettings.serviceVersion(Map.empty) shouldBe "dev"
    }
  }

  "logback.xml" should {
    "write every line through LogEncoder to stdout, root and slick at info by default" in {
      assume(!sys.env.contains("LOG_LEVEL") && !sys.env.contains("SLICK_LOG_LEVEL"))
      val ctx = new LoggerContext()
      val configurator = new JoranConfigurator()
      configurator.setContext(ctx)
      configurator.doConfigure(getClass.getResource("/logback.xml"))
      new StatusUtil(ctx).getHighestLevel(0) should be < Status.WARN
      val root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
      root.getLevel shouldBe Level.INFO
      ctx.getLogger("slick").getLevel shouldBe Level.INFO
      val console = root.getAppender("CONSOLE").asInstanceOf[ConsoleAppender[_]]
      console.getTarget shouldBe "System.out"
      console.getEncoder shouldBe a[LogEncoder]
      root.iteratorForAppenders().asScala.toList should have size 1
      ctx.stop()
    }
    "prefer a system property to the environment, so an empty LOG_LEVEL gets the default" in {
      System.setProperty("LOG_LEVEL", "error")
      try {
        val ctx = new LoggerContext()
        val configurator = new JoranConfigurator()
        configurator.setContext(ctx)
        configurator.doConfigure(getClass.getResource("/logback.xml"))
        ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel shouldBe Level.ERROR
        ctx.stop()
      } finally System.clearProperty("LOG_LEVEL")
    }
  }
}
