package at.energydash.actors.http

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCode, StatusCodes, Uri}
import org.apache.pekko.pattern.after
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import org.apache.pekko.util.Timeout
import at.energydash.actors.MqttPublisher.{EdaNotification, MqttCommand, MqttPublish, MqttPublishError}
import at.energydash.domain.{EbMsMessage, XmlParseHandler}
import at.energydash.domain.eda.MessageHelper.EDAMessageCodeToProcessCode
import org.slf4j.{Logger, LoggerFactory}
import soapenvelope11.Envelope

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
import scala.xml.NodeSeq

class PontonRoute(mqttPublisher: ActorRef[MqttCommand],
                  forwardTargets: Map[String, Uri] = Map.empty,
                  forwardTimeout: FiniteDuration = InboundForwarding.DefaultForwardTimeout,
                  readTimeout: FiniteDuration = InboundForwarding.DefaultReadTimeout,
                  maxBodyBytes: Long,
                  publishTimeout: FiniteDuration = PontonRoute.DefaultPublishTimeout)(implicit val system: ActorSystem[_]) {

  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = system.scheduler
  implicit val ec: ExecutionContext = system.executionContext

  var logger: Logger = LoggerFactory.getLogger(this.getClass)

  logger.debug("Start Ponton Routes ...")
  if (forwardTargets.nonEmpty) {
    logger.info(s"Inbound messages forwarded to another zone for: ${forwardTargets.map { case (rc, uri) => s"$rc -> $uri" }.mkString(", ")} (read within $readTimeout, answer within $forwardTimeout)")
    // `infinite` is a valid request-timeout and has no Duration: then nothing to warn about
    val serverTimeout = Try(system.settings.config.getDuration("pekko.http.server.request-timeout").toMillis.millis).toOption
    if (serverTimeout.exists(readTimeout + forwardTimeout >= _))
      logger.warn(s"app.forward-read-timeout + app.forward-timeout ($readTimeout + $forwardTimeout) reach pekko.http.server.request-timeout (${serverTimeout.get}): the server's 503 can answer before the forward result")
  }

  /**
   * Hands an inbound message on unchanged (body, content type, SOAPAction) to the adapter of the
   * zone the community moved to. Its status decides what Ponton gets back: anything but 2xx, or no
   * answer within forwardTimeout, fails the request so Ponton redelivers (at-least-once). The request
   * to the target is not cancelled on timeout: a target that finishes after it processes the message,
   * and then Ponton's redelivery a second time (an extra history/notification row in its backend,
   * energy slots overwritten with the same values).
   * The client connection gets the same idle timeout, so a hanging target does not keep pool slots.
   * Time waiting in the pool queue counts against forwardTimeout, so the pool is sized for a burst of
   * energy data (pekko's default is 4 connections / 32 queued requests).
   */
  private val forwardPool: ConnectionPoolSettings =
    ConnectionPoolSettings(system)
      .withMaxConnections(16)
      .withMaxOpenRequests(256)
      .withConnectionSettings(ClientConnectionSettings(system).withIdleTimeout(forwardTimeout))

  private def forward(target: Uri, request: HttpRequest): Future[StatusCode] = {
    val headers = request.headers.filter(_.is("soapaction")) :+ RawHeader(InboundForwarding.ForwardedHeader, "true")
    val sent = Http().singleRequest(HttpRequest(HttpMethods.POST, target, headers, request.entity), settings = forwardPool).map { r =>
      r.discardEntityBytes()
      r.status
    }
    val timedOut = after(forwardTimeout)(Future.failed(new TimeoutException(s"no answer from $target within $forwardTimeout")))
    Future.firstCompletedOf(Seq(sent, timedOut))
  }

  /** Makes real message sizes visible (search key ponton-message-size); no content is logged. */
  private val bodyLimit = new BodySizeLimit(maxBodyBytes, logger, "Ponton request")

  private def logSize(size: Option[Long], details: => String): Unit =
    size.filter(_ > PontonRoute.LogSizeAboveBytes).foreach(b => logger.info(s"ponton-message-size: $b bytes, $details"))

  /**
   * Hands the notification to the publisher and completes when the broker confirmed it (QoS 1
   * PUBACK). Fails when the publisher reports it as not published, or after publishTimeout (also
   * covers an event dropped by a full MQTT buffer or lost in a client restart). A confirmation after
   * the timeout is ignored: the message is then at the broker and, after Ponton's redelivery, a
   * second time (at-least-once, as with forwarding).
   */
  private def publish(notification: EdaNotification): Future[Done] = {
    val delivered = Promise[Done]()
    val timer = system.scheduler.scheduleOnce(publishTimeout, () =>
      delivered.tryFailure(new TimeoutException(s"no broker confirmation within $publishTimeout")))
    delivered.future.onComplete(_ => timer.cancel())
    mqttPublisher ! MqttPublish(notification :: Nil, Some(delivered))
    delivered.future
  }

  private def processLocally(response: NodeSeq, size: Option[Long]): Route =
    onComplete(Future {
      logger.debug(s"Receive message from edaAdapter. Length ${response.head.length}")
      scalaxb.fromXML[Envelope](response.head)
    }.flatMap(e => XmlParseHandler.reponseEbMsMessage(e))) {
      case Success(x) =>
        logSize(size, s"message code ${x.messageCode}, conversation ${x.conversationId}")
        val notification = PontonRoute.notificationFor(x)
        if (notification.protocol == PontonRoute.ErrorProtocol)
          logger.error(s"Inbound message from edaAdapter not processed (conversation ${x.conversationId}, receiver ${x.receiver}): ${notification.message.errorMessage.getOrElse("")}")
        // 204 only once the broker confirmed the publish; anything else is a 500, so Ponton keeps
        // the message and redelivers it instead of it being lost silently (261005-ca1)
        val started = System.nanoTime()
        onComplete(publish(notification)) {
          case Success(_) =>
            complete(HttpResponse(StatusCodes.NoContent))
          case Failure(ex) =>
            val ms = (System.nanoTime() - started) / 1000000
            logger.error(s"Inbound message (conversation ${x.conversationId}, message code ${x.messageCode}) not published to MQTT after $ms ms: ${ex.getMessage}; Ponton will redeliver")
            complete(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
        }
      case Failure(ex) =>
        logSize(size, "not parsed")
        // The message does not parse, or a known document cannot be mapped (e.g. a new
        // MessageCode): keep the 500 so Ponton holds it and redelivers it after a fix
        // (team decision, platform#111 ca11). Only unknown document types are
        // acknowledged above, because eda-xp can never process them.
        logger.error("Error while parsing message from edaAdapter", ex)
        mqttPublisher ! MqttPublishError("NotSpecified", ex.getMessage)
        complete(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
    }

  val pontonRoutes: Route = pathPrefix("pontonxp") {
    // inside the prefix: the routes are bound together with adminRoutes, which has its own limit
    bodyLimit.directive {
      concat(
        path("notification") {
          post {
            extractRequest { request =>
              onComplete(Future {
//                println("Notification")
//                println(request.headers)
              }.flatMap(_=>request.entity.toStrict(1 second))) {
                case Success(d) =>
//                  println(d.data.utf8String)
                  logger.info(s"Notification from EDA: ${d.data.utf8String}")
                  complete(HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
                case Failure(f) =>
                  logger.error(f.getMessage)
                  complete(HttpResponse(StatusCodes.BadRequest, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
              }
            }
          }
        } ~
        path("status") {
          post {
            extractRequest { request =>
              onComplete {
//                  println("Status")
//                  println(request.headers)
                request.entity.toStrict(1 second)
              } {
                case Success(d) =>
//                    println(d.data.utf8String)
                  logger.info(s"Status from EDA: ${d.data.utf8String}")
                  complete(HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
                case Failure(e) =>
                  logger.error(e.toString)
                  complete(HttpResponse(StatusCodes.BadRequest, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
              }
            }
          }
        } ~
        path("message") {
          post {
            if (forwardTargets.isEmpty)
              // no community forwarded: no read deadline, the body is parsed while it streams in
              // (capped by bodyLimit); its size is known when the request declares a Content-Length
              extractRequest { request =>
                entity(as[NodeSeq])(processLocally(_, request.entity.contentLengthOption))
              }
            else
              // strict, so the unchanged body can be forwarded after reading the receiver; capped at
              // maxBodyBytes (the default max-to-strict-bytes of 8 MiB would reject large energy
              // data), and every message, forwarded or not, must arrive within readTimeout
              toStrictEntity(readTimeout, maxBodyBytes) {
                extractRequest { request =>
                  entity(as[NodeSeq]) { response =>
                    val size = request.entity.contentLengthOption
                    val receiver = InboundForwarding.receiverOf(response)
                    val alreadyForwarded = request.headers.exists(_.is(InboundForwarding.ForwardedHeader.toLowerCase))
                    InboundForwarding.decide(forwardTargets, receiver, alreadyForwarded) match {
                      case InboundForwarding.Local => processLocally(response, size)
                      case InboundForwarding.Loop(rc, target) =>
                        logger.error(s"Inbound message for $rc arrived already forwarded, but $rc is forwarded again to $target here; refusing it to avoid a loop")
                        complete(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
                      case InboundForwarding.Forward(rc, target) =>
                        logSize(size, s"forwarded for $rc")
                        // No MqttPublishError on failure: this zone's backend does not own the
                        // community any more; the error stays in this log and Ponton redelivers.
                        val started = System.nanoTime()
                        onComplete(forward(target, request)) { result =>
                          val ms = (System.nanoTime() - started) / 1000000
                          result match {
                            case Success(status) if status.isSuccess() =>
                              logger.info(s"Inbound message for $rc forwarded to $target: $status in $ms ms")
                              complete(HttpResponse(StatusCodes.NoContent))
                            case Success(StatusCodes.ContentTooLarge) =>
                              logger.error(s"Inbound message for $rc (${size.fold("size unknown")(b => s"$b bytes")}) refused as too large by $target: its app.max-body-size is below this zone's ($maxBodyBytes bytes); raise it there. Ponton will redeliver")
                              complete(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
                            case Success(status) =>
                              logger.error(s"Inbound message for $rc not accepted by $target: $status in $ms ms; Ponton will redeliver")
                              complete(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
                            case Failure(ex) =>
                              logger.error(s"Inbound message for $rc could not be forwarded to $target after $ms ms: ${ex.getMessage}; Ponton will redeliver")
                              complete(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
                          }
                        }
                    }
                  }
                }
              }
          }
        }
      )
    }
  }
}

object PontonRoute {
  val ErrorProtocol = "ERROR"

  /** Inbound messages above this size are logged at INFO with their size (ponton-message-size). */
  val LogSizeAboveBytes: Long = 16L * 1024 * 1024

  val DefaultPublishTimeout: FiniteDuration = 10.seconds

  /**
   * `app.inbound-publish-timeout` (env INBOUND_PUBLISH_TIMEOUT, with unit, e.g. `10s`): how long an
   * inbound message waits for the broker's confirmation before Ponton gets a 500. Below 1 s stops
   * the start (a number without unit is milliseconds).
   */
  def publishTimeout(config: com.typesafe.config.Config): FiniteDuration = {
    val t = if (config.hasPath("app.inbound-publish-timeout")) config.getDuration("app.inbound-publish-timeout").toMillis.millis else DefaultPublishTimeout
    if (t < 1.second)
      throw new IllegalArgumentException(s"app.inbound-publish-timeout must be at least 1s (give a unit, e.g. 10s): $t")
    t
  }

  /*
   * Deadlines: app.forward-read-timeout / app.forward-timeout (InboundForwarding.timeouts). Reading the
   * body, parsing it and forwarding normally stay below the server's request-timeout (pekko default
   * 20 s, counted from the start of the request), so the forward result decides the answer; the route
   * warns at start when the configured values reach it. A very large, slowly read body can still run
   * into the server's own 503; Ponton then redelivers as well. Ponton's EDA add-on waits 30 s
   * (eda.adapter.httpclient_timeout).
   */

  /**
   * MQTT notification for an inbound message. Messages without a process mapping (unknown or
   * unregistered message types, ERROR_MESSAGE) go to the error protocol of the receiver instead of
   * failing the request (platform#111 ca11).
   */
  def notificationFor(msg: EbMsMessage): EdaNotification =
    Try(EDAMessageCodeToProcessCode(msg.messageCode)).toOption match {
      case Some(process) => EdaNotification(process.toString, msg)
      case None => EdaNotification(ErrorProtocol, msg.copy(
        errorMessage = msg.errorMessage.orElse(Some(s"No process for message code ${msg.messageCode}"))))
    }
}
