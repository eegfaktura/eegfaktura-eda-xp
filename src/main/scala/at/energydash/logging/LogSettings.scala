package at.energydash.logging

import java.util.Locale

/**
 * The environment of the logging module (logging concept W-261008-ca2, point 1). `logback.xml` reads the same
 * variables; [[stopOnInvalid]] runs first in `XpAdapter`, because logback itself takes an unknown level as DEBUG.
 *  - `LOG_FORMAT`: `json` (set in the image) or `text` (default when run from source).
 *  - `LOG_LEVEL`: `trace`, `debug`, `info` (default), `warn`, `error` — the root level.
 *  - `SLICK_LOG_LEVEL`: the same values, for the `slick` loggers only (default `info`).
 *  - `SERVICE_VERSION`: `service.version` of every JSON line (set in the image), default `dev`.
 */
object LogSettings {
  val ServiceName = "eegfaktura-eda-xp"
  val Levels: Seq[String] = Seq("trace", "debug", "info", "warn", "error")
  val Formats: Seq[String] = Seq("json", "text")

  private def normalize(value: String): String = value.trim.toLowerCase(Locale.ROOT)

  /** `true` for the text format; an unknown value is an error. */
  def isText(format: String): Either[String, Boolean] = normalize(format) match {
    case "json" => Right(false)
    case "text" => Right(true)
    case _ => Left(s"LOG_FORMAT must be one of ${Formats.mkString(", ")}, not '$format'")
  }

  /** The errors in the logging variables of `env` (a variable that is not set is fine). */
  def check(env: Map[String, String]): List[String] = {
    val format = env.get("LOG_FORMAT").flatMap(f => isText(f).left.toOption)
    val levels = Seq("LOG_LEVEL", "SLICK_LOG_LEVEL").flatMap { name =>
      env.get(name).filterNot(v => Levels.contains(normalize(v)))
        .map(v => s"$name must be one of ${Levels.mkString(", ")}, not '$v'")
    }
    (format ++ levels).toList
  }

  def serviceVersion(env: Map[String, String]): String = env.get("SERVICE_VERSION").map(_.trim).filter(_.nonEmpty).getOrElse("dev")

  /** Ends the start (exit code 2, message on stderr) when a logging variable has an unknown value. */
  def stopOnInvalid(): Unit = {
    val errors = check(sys.env)
    if (errors.nonEmpty) {
      errors.foreach(e => System.err.println(s"eda-xp: $e"))
      sys.exit(2)
    }
  }
}
