package at.energydash.logging

import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.status.{Status, StatusUtil}
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
