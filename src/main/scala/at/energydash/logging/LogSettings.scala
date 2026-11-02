package at.energydash.logging

import ch.qos.logback.classic.LoggerContext
import org.slf4j.bridge.SLF4JBridgeHandler
import org.slf4j.{Logger, LoggerFactory}

import java.util.Locale

/**
 * The environment of the logging module (logging concept W-261008-ca2, point 1). `logback.xml` reads the same
 * variables; [[stopOnInvalid]] and [[fillEmpty]] run first through [[LogStartup]], because logback itself takes an unknown
 * level as DEBUG and an empty one as not given (the root logger then stays at DEBUG). An empty variable means the
 * default, as in the other services.
 *  - `LOG_FORMAT`: `json` (set in the image) or `text` (default when run from source).
 *  - `LOG_LEVEL`: `trace`, `debug`, `info` (default), `warn`, `error` — the root level.
 *  - `SLICK_LOG_LEVEL`: the same values, for the `slick` loggers only (default `info`).
 *  - `SERVICE_VERSION`: `service.version` of every JSON line (set in the image), default `dev`.
 *  - `DEPLOYMENT_ENVIRONMENT`: `deployment.environment` of every JSON line; the field is left out when unset or empty.
 */
/** Mixed in first by the main object, so the settings are checked and filled before the first logger exists. */
trait LogStartup {
  LogSettings.stopOnInvalid()
  LogSettings.fillEmpty()
  LogSettings.bridgeJavaUtilLogging()
}

object LogSettings {
  val ServiceName = "eegfaktura-eda-xp"
  val Levels: Seq[String] = Seq("trace", "debug", "info", "warn", "error")
  val Formats: Seq[String] = Seq("json", "text")
  /** At info in `logback.xml`, so the start-up line is written at any `LOG_LEVEL`. */
  val StartLogger = "at.energydash.start"

  private val DefaultsForEmpty = Map("LOG_LEVEL" -> "info", "SLICK_LOG_LEVEL" -> "info", "LOG_FORMAT" -> "json")

  private def normalize(value: String): String = value.trim.toLowerCase(Locale.ROOT)

  /** `true` for the text format; an unknown value is an error. */
  def isText(format: String): Either[String, Boolean] = normalize(format) match {
    case "json" => Right(false)
    case "text" => Right(true)
    case _ => Left(s"LOG_FORMAT must be one of ${Formats.mkString(", ")}, not '$format'")
  }

  /** The errors in the logging variables of `env` (a variable that is not set or empty is fine). */
  def check(env: Map[String, String]): List[String] = {
    val format = env.get("LOG_FORMAT").filter(_.trim.nonEmpty).flatMap(f => isText(f).left.toOption)
    val levels = Seq("LOG_LEVEL", "SLICK_LOG_LEVEL").flatMap { name =>
      env.get(name).filter(_.trim.nonEmpty).filterNot(v => Levels.contains(normalize(v)))
        .map(v => s"$name must be one of ${Levels.mkString(", ")}, not '$v'")
    }
    (format ++ levels).toList
  }

  /** The defaults of the logging variables that are set but empty in `env`. */
  def defaultsForEmpty(env: Map[String, String]): Map[String, String] =
    DefaultsForEmpty.filter { case (name, _) => env.get(name).exists(_.trim.isEmpty) }

  /** Before the first logger: logback reads a system property ahead of the environment. */
  def fillEmpty(): Unit = defaultsForEmpty(sys.env).foreach { case (name, value) => System.setProperty(name, value) }

  /** java.util.logging (gRPC, pgjdbc, paho) goes through SLF4J and the encoder, otherwise it prints plain text to stderr. */
  def bridgeJavaUtilLogging(): Unit =
    if (!SLF4JBridgeHandler.isInstalled) {
      SLF4JBridgeHandler.removeHandlersForRootLogger()
      SLF4JBridgeHandler.install()
    }

  def serviceVersion(env: Map[String, String]): String = env.get("SERVICE_VERSION").map(_.trim).filter(_.nonEmpty).getOrElse("dev")

  def deploymentEnvironment(env: Map[String, String]): Option[String] = env.get("DEPLOYMENT_ENVIRONMENT").map(_.trim).filter(_.nonEmpty)

  /** The one start-up line: version and effective log levels, nothing else of the configuration. */
  def logStart(): Unit = {
    val levels = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    def level(name: String) = levels.getLogger(name).getEffectiveLevel.toString.toLowerCase(Locale.ROOT)
    LoggerFactory.getLogger(StartLogger).atInfo().addKeyValue(LogFields.EventAction, "service.start")
      .setMessage(s"Service start: version ${serviceVersion(sys.env)}, log level ${level(Logger.ROOT_LOGGER_NAME)}, slick log level ${level("slick")}")
      .log()
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
