package at.energydash.actors.http

import com.typesafe.config.{Config => TsConfig}
import org.apache.pekko.http.scaladsl.model.{EntityStreamSizeException, HttpResponse, StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{Directive0, ExceptionHandler, MalformedRequestContentRejection, RejectionHandler, Route, StandardRoute}
import org.slf4j.Logger

/**
 * Upper bound for request bodies that are read completely into memory (Ponton messages, admin
 * upload). Larger requests get TooLargeStatus and a WARN line; nothing of them is parsed.
 * Built once per route class: `what` names the route in the log.
 */
class BodySizeLimit(maxBytes: Long, logger: Logger, what: String) {

  private val tooLarge: Route = extractRequest { request =>
    val declared = request.entity.contentLengthOption
    logger.warn(s"$what rejected: body larger than the limit of $maxBytes bytes (declared size: ${declared.fold("none")(b => s"$b bytes")})")
    complete(HttpResponse(BodySizeLimit.TooLargeStatus))
  }

  private val exceptions = ExceptionHandler { case _: EntityStreamSizeException => tooLarge }

  private val rejections = RejectionHandler.newBuilder().handle {
    case MalformedRequestContentRejection(_, _: EntityStreamSizeException) => tooLarge
  }.result()

  /**
   * Caps the body at maxBytes. A declared Content-Length above it is answered before anything is
   * read; a body without one (chunked) fails as soon as it passes the limit.
   */
  val directive: Directive0 =
    extractRequest.flatMap { request =>
      if (request.entity.contentLengthOption.exists(_ > maxBytes)) StandardRoute(tooLarge).toDirective[Unit]
      else withSizeLimit(maxBytes) & handleExceptions(exceptions) & handleRejections(rejections)
    }
}

object BodySizeLimit {

  /**
   * Answer to a body above the limit. 413 for now; whether Ponton should get 500 instead (so it keeps
   * and redelivers the message) is an open decision, which is why it is a single constant.
   */
  val TooLargeStatus: StatusCode = StatusCodes.ContentTooLarge

  /** `app.max-body-size` (reference.conf, env MAX_BODY_SIZE, with unit, e.g. 256m). */
  def maxBytes(config: TsConfig): Long = {
    val bytes = config.getBytes("app.max-body-size").longValue
    if (bytes < 1) throw new IllegalArgumentException(s"app.max-body-size must be positive (give a unit, e.g. 128m): $bytes")
    bytes
  }
}
