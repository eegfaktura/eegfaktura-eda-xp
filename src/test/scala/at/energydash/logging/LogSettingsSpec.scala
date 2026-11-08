package at.energydash.logging

import at.energydash.testsupport.LogCapture
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.status.{Status, StatusUtil}
import com.fasterxml.jackson.databind.ObjectMapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.bridge.SLF4JBridgeHandler

import scala.jdk.CollectionConverters._

/** The settings of the logging module: variables, `logback.xml`, the start-up line and java.util.logging. */
class LogSettingsSpec extends AnyWordSpec with Matchers {
  private val mapper = new ObjectMapper()

  /** A context configured from `logback.xml`, with `properties` in place of the environment variables. */
  private def configured[T](properties: (String, String)*)(body: LoggerContext => T): T = {
    val ctx = new LoggerContext()
    properties.foreach { case (k, v) => ctx.putProperty(k, v) }
    val configurator = new JoranConfigurator()
    configurator.setContext(ctx)
    configurator.doConfigure(getClass.getResource("/logback.xml"))
    try body(ctx) finally ctx.stop()
  }

  private def encoderOf(ctx: LoggerContext): LogEncoder =
    ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE").asInstanceOf[ConsoleAppender[_]].getEncoder.asInstanceOf[LogEncoder]

  private def encoded(ctx: LoggerContext): String = {
    val e = new LoggingEvent("x", ctx.getLogger("at.energydash.Test"), Level.INFO, "m", null, null)
    e.setMDCPropertyMap(java.util.Collections.emptyMap())
    new String(encoderOf(ctx).encode(e), "UTF-8")
  }

  "LogSettings" should {
    "accept the five levels, warning, and two formats in any case, and unset or empty variables" in {
      LogSettings.check(Map("LOG_LEVEL" -> "TRACE", "SLICK_LOG_LEVEL" -> "warn", "LOG_FORMAT" -> "Json")) shouldBe Nil
      LogSettings.check(Map("LOG_LEVEL" -> "warning", "SLICK_LOG_LEVEL" -> "Warning")) shouldBe Nil
      LogSettings.check(Map.empty) shouldBe Nil
      LogSettings.check(Map("LOG_LEVEL" -> "", "SLICK_LOG_LEVEL" -> " ", "LOG_FORMAT" -> "")) shouldBe Nil
    }
    "refuse an unknown value" in {
      LogSettings.check(Map("LOG_LEVEL" -> "verbose")) should have size 1
      LogSettings.check(Map("SLICK_LOG_LEVEL" -> "fine")) should have size 1
      LogSettings.check(Map("LOG_FORMAT" -> "ecs")).head should include("LOG_FORMAT")
    }
    "give the logback level, info for an unset or empty variable" in {
      LogSettings.levelOf(Map("LOG_LEVEL" -> "warning"), "LOG_LEVEL") shouldBe "WARN"
      LogSettings.levelOf(Map("LOG_LEVEL" -> " Debug "), "LOG_LEVEL") shouldBe "DEBUG"
      LogSettings.levelOf(Map("LOG_LEVEL" -> " "), "LOG_LEVEL") shouldBe "INFO"
      LogSettings.levelOf(Map.empty, "LOG_LEVEL") shouldBe "INFO"
    }
  }

  "LogLevelDefiner" should {
    "take a context property, then a JVM system property, then the environment" in {
      val name = "EDA_XP_LOG_LEVEL_DEFINER_TEST"
      def level(ctx: LoggerContext) = {
        val definer = new LogLevelDefiner()
        definer.setContext(ctx)
        definer.setEnv(name)
        definer.getPropertyValue
      }
      System.setProperty(name, "warning")
      try {
        level(new LoggerContext()) shouldBe "WARN"
        val ctx = new LoggerContext()
        ctx.putProperty(name, "error")
        level(ctx) shouldBe "ERROR"
      } finally System.clearProperty(name)
      level(new LoggerContext()) shouldBe "INFO"
    }
  }

  "logback.xml" should {
    "write every line through LogEncoder to stdout, as JSON, root and slick at info, also for empty variables" in {
      configured("LOG_LEVEL" -> "", "SLICK_LOG_LEVEL" -> "", "LOG_FORMAT" -> "") { ctx =>
        new StatusUtil(ctx).getHighestLevel(0) should be < Status.WARN
        val root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        root.getLevel shouldBe Level.INFO
        ctx.getLogger("slick").getLevel shouldBe Level.INFO
        val console = root.getAppender("CONSOLE").asInstanceOf[ConsoleAppender[_]]
        console.getTarget shouldBe "System.out"
        root.iteratorForAppenders().asScala.toList should have size 1
        encoded(ctx) should startWith("{\"@timestamp\"")
      }
    }
    "take the levels and the format from the variables" in {
      configured("LOG_LEVEL" -> "warning", "SLICK_LOG_LEVEL" -> "error", "LOG_FORMAT" -> "text") { ctx =>
        ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel shouldBe Level.WARN
        ctx.getLogger("slick").getLevel shouldBe Level.ERROR
        encoded(ctx) should include(" info  at.energydash.Test: m")
      }
    }
    "give the encoder SERVICE_VERSION and DEPLOYMENT_ENVIRONMENT, an empty one as unset" in {
      val fields = configured("SERVICE_VERSION" -> "0.2.22", "DEPLOYMENT_ENVIRONMENT" -> " pilot ")(ctx => mapper.readTree(encoded(ctx)))
      fields.get("service.version").asText shouldBe "0.2.22"
      fields.get("deployment.environment").asText shouldBe "pilot"
      val empty = configured("SERVICE_VERSION" -> " ", "DEPLOYMENT_ENVIRONMENT" -> "")(ctx => mapper.readTree(encoded(ctx)))
      empty.get("service.version").asText shouldBe "dev"
      empty.has("deployment.environment") shouldBe false
    }
  }

  "The start-up line (dev#45 L14)" should {
    "have the fixed text, the action and the effective level" in {
      val (_, events) = LogCapture(LogSettings.StartLogger)(LogSettings.logStart())
      events should have size 1
      events.head.getFormattedMessage shouldBe "service start"
      events.head.getKeyValuePairs.asScala.map(kv => kv.key -> kv.value).toMap shouldBe
        Map("event.action" -> "service.start", "log.effective_level" -> "info")
    }
    "be written at any LOG_LEVEL" in {
      configured("LOG_LEVEL" -> "error") { ctx =>
        ctx.getLogger(LogSettings.StartLogger).isInfoEnabled shouldBe true
        ctx.getLogger("at.energydash.Other").isInfoEnabled shouldBe false
      }
    }
  }

  "java.util.logging" should {
    "come out through SLF4J as a JSON line, with the exception (dev#45 L13)" in {
      val installed = SLF4JBridgeHandler.isInstalled
      val (_, events) = try {
        LogSettings.bridgeJavaUtilLogging()
        LogSettings.bridgeJavaUtilLogging() // once only, or each line would come twice
        val jul = java.util.logging.Logger.getLogger("io.grpc.Test")
        LogCapture("io.grpc.Test") {
          jul.log(java.util.logging.Level.WARNING, "channel broken", new IllegalStateException("boom"))
        }
      } finally if (!installed) {
        // the JVM's own java.util.logging setup back, as the suites after this one found it
        SLF4JBridgeHandler.uninstall()
        java.util.logging.LogManager.getLogManager.readConfiguration()
      }
      events should have size 1
      val fields = mapper.readValue(EcsJson.line(events.head, "eegfaktura-eda-xp", "dev"), classOf[java.util.Map[String, Any]]).asScala
      fields("log.logger") shouldBe "io.grpc.Test"
      fields("log.level") shouldBe "warn"
      fields("message") shouldBe "channel broken"
      fields("error.type") shouldBe "java.lang.IllegalStateException"
    }
  }
}
