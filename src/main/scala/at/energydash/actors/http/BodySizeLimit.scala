package at.energydash.actors.http

import com.typesafe.config.{Config => TsConfig}
import org.apache.pekko.http.scaladsl.model.{EntityStreamSizeException, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{Directive0, ExceptionHandler, MalformedRequestContentRejection, RejectionHandler}
import org.slf4j.Logger

/**
 * Upper bound for request bodies that are read completely into memory (Ponton messages, admin
 * upload). Larger requests get 413 and a WARN line; nothing of them is parsed.
 */
object BodySizeLimit {

  /** `app.max-body-size` (env MAX_BODY_SIZE, with unit, e.g. 256m). */
  def maxBytes(config: TsConfig): Long = {
    val bytes = if (config.hasPath("app.max-body-size")) config.getBytes("app.max-body-size").longValue else DefaultMaxBytes
    if (bytes < 1) throw new IllegalArgumentException(s"app.max-body-size must be positive (give a unit, e.g. 128m): $bytes")
    bytes
  }

  val DefaultMaxBytes: Long = 128L * 1024 * 1024

  /**
   * Caps the body at maxBytes. A declared Content-Length above it is answered with 413 before
   * anything is read; a body without one (chunked) fails with 413 as soon as it passes the limit.
   */
  def apply(maxBytes: Long, logger: Logger, what: String): Directive0 =
    extractRequest.flatMap { request =>
      val declared = request.entity.contentLengthOption
      def tooLarge = {
        logger.warn(s"$what rejected: body larger than the limit of $maxBytes bytes (declared size: ${declared.fold("none")(b => s"$b bytes")})")
        complete(HttpResponse(StatusCodes.ContentTooLarge))
      }
      if (declared.exists(_ > maxBytes)) tooLarge.toDirective[Unit]
      else
        withSizeLimit(maxBytes) &
          handleExceptions(ExceptionHandler { case _: EntityStreamSizeException => tooLarge }) &
          handleRejections(RejectionHandler.newBuilder().handle {
            case MalformedRequestContentRejection(_, _: EntityStreamSizeException) => tooLarge
          }.result())
    }
}
