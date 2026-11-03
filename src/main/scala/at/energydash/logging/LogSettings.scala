package at.energydash.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.PropertyDefinerBase
import org.slf4j.bridge.SLF4JBridgeHandler
import org.slf4j.{Logger, LoggerFactory}

import java.util.Locale

/** Mixed in first by the main object, so the settings are checked before the first logger exists. */
trait LogStartup {
  LogSettings.stopOnInvalid()
  LogSettings.bridgeJavaUtilLogging()
  LogSettings.logStart()
}

/**
 * The environment of the logging module (logging concept W-261008-ca2, point 1), read by `logback.xml`. An empty
 * variable is the same as an unset one (the default applies), as in the other services. [[stopOnInvalid]] runs first
 * through [[LogStartup]], because logback itself takes an unknown level as DEBUG.
 *  - `LOG_FORMAT`: `json` (default) or `text`, a readable line for local runs.
 *  - `LOG_LEVEL`: `trace`, `debug`, `info` (default), `warn` (also `warning`), `error` — the root level.
 *  - `SLICK_LOG_LEVEL`: the same values, for the `slick` loggers only (default `info`).
 *  - `SERVICE_VERSION`: `service.version` of every line (set in the image), default `dev`.
 *  - `DEPLOYMENT_ENVIRONMENT`: `deployment.environment` of every line; the field is left out when unset or empty.
 */
object LogSettings {
  val ServiceName = "eegfaktura-eda-xp"
  val Formats: Seq[String] = Seq("json", "text")
  /** At info in `logback.xml`, so the start-up line is written at any `LOG_LEVEL`. */
  val StartLogger = "at.energydash.start"

  private val Levels = Map(
    "trace" -> "TRACE", "debug" -> "DEBUG", "info" -> "INFO", "warn" -> "WARN", "warning" -> "WARN", "error" -> "ERROR")

  private def normalize(value: String): String = value.trim.toLowerCase(Locale.ROOT)

  /** The logback level name of a `LOG_LEVEL` value, none when it is not one of the allowed values. */
  def parseLevel(value: String): Option[String] = Option(value).flatMap(v => Levels.get(normalize(v)))

  def parseFormat(value: String): Option[String] = Option(value).map(normalize).filter(Formats.contains)

  /** The value of a variable; unset and empty are the same (none). */
  private def valueOf(env: Map[String, String], name: String): Option[String] = env.get(name).filter(_.trim.nonEmpty)

  /** The errors in the logging variables of `env` (a variable that is not set or empty is fine). */
  def check(env: Map[String, String]): List[String] = {
    val format = valueOf(env, "LOG_FORMAT").filter(parseFormat(_).isEmpty)
      .map(v => s"LOG_FORMAT must be one of ${Formats.mkString(", ")}, not '$v'")
    val levels = Seq("LOG_LEVEL", "SLICK_LOG_LEVEL").flatMap { name =>
      valueOf(env, name).filter(parseLevel(_).isEmpty).map(v => s"$name must be one of trace, debug, info, warn, error, not '$v'")
    }
    (format ++ levels).toList
  }

  /** The logback level `logback.xml` uses for `variable`: its value, or info when it is unset or empty. */
  def levelOf(env: Map[String, String], variable: String): String =
    valueOf(env, variable).flatMap(parseLevel).getOrElse("INFO")

  /** java.util.logging (gRPC, pgjdbc, paho) goes through SLF4J and the encoder, otherwise it prints plain text to stderr. */
  def bridgeJavaUtilLogging(): Unit =
    if (!SLF4JBridgeHandler.isInstalled) {
      SLF4JBridgeHandler.removeHandlersForRootLogger()
      SLF4JBridgeHandler.install()
    }

  /**
   * The one start-up line: `service.version` as on every line, and the effective root level. Its logger is at info in
   * `logback.xml`, so `LOG_LEVEL=warn` does not switch it off.
   */
  def logStart(context: LoggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]): Unit = {
    val level = context.getLogger(Logger.ROOT_LOGGER_NAME).getEffectiveLevel.toString.toLowerCase(Locale.ROOT)
    context.getLogger(StartLogger).atInfo()
      .addKeyValue(LogFields.EventAction, "service.start")
      .addKeyValue(LogFields.LogEffectiveLevel, level)
      .log("service start")
  }

  /** Ends the start (exit code 2, message on stderr) when a logging variable has an unknown value. */
  def stopOnInvalid(): Unit = {
    val errors = check(sys.env)
    if (errors.nonEmpty) {
      errors.foreach(e => System.err.println(s"eda-xp: $e"))
      sys.exit(2)
    }
  }
}

/**
 * `<define>` in `logback.xml`: the level of the variable `env` as logback level, info when it is unset or empty. A
 * logback context property of the same name comes before the environment, as in logback's own `${...}` (the tests
 * configure a context this way).
 */
class LogLevelDefiner extends PropertyDefinerBase {
  private var variable = "LOG_LEVEL"

  def setEnv(value: String): Unit = variable = value

  override def getPropertyValue: String = {
    val value = Option(getContext.getProperty(variable)).orElse(sys.env.get(variable))
    LogSettings.levelOf(value.map(variable -> _).toMap, variable)
  }
}
