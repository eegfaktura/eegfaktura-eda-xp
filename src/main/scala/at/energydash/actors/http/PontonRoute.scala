package at.energydash.actors.http

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
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
import scala.xml.NodeSeq

class PontonRoute(mqttPublisher: ActorRef[MqttCommand],
                  forwardTargets: Map[String, Uri] = Map.empty,
                  forwardTimeout: FiniteDuration = PontonRoute.ForwardTimeout)(implicit val system: ActorSystem[_]) {

  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = system.scheduler
  implicit val ec: ExecutionContext = system.executionContext

  var logger: Logger = LoggerFactory.getLogger(this.getClass)

  logger.debug("Start Ponton Routes ...")
  if (forwardTargets.nonEmpty)
    logger.info(s"Inbound messages forwarded to another zone for: ${forwardTargets.map { case (rc, uri) => s"$rc -> $uri" }.mkString(", ")}")

  /**
   * Hands an inbound message on unchanged (body, content type, SOAPAction) to the adapter of the
   * zone the community moved to. Its status decides what Ponton gets back: anything but 2xx, or no
   * answer within forwardTimeout, fails the request so Ponton redelivers (at-least-once, as Ponton's
   * own redelivery already is: a target that answers after the timeout may see the message twice).
   * The client connection gets the same idle timeout, so a hanging target does not keep pool slots.
   */
  private val forwardPool: ConnectionPoolSettings =
    ConnectionPoolSettings(system).withConnectionSettings(ClientConnectionSettings(system).withIdleTimeout(forwardTimeout))

  private def forward(target: Uri, request: HttpRequest): Future[StatusCode] = {
    val headers = request.headers.filter(_.is("soapaction")) :+ RawHeader(InboundForwarding.ForwardedHeader, "true")
    val sent = Http().singleRequest(HttpRequest(HttpMethods.POST, target, headers, request.entity), settings = forwardPool).map { r =>
      r.discardEntityBytes()
      r.status
    }
    val timedOut = after(forwardTimeout)(Future.failed(new TimeoutException(s"no answer from $target within $forwardTimeout")))
    Future.firstCompletedOf(Seq(sent, timedOut))
  }

  private def processLocally(response: NodeSeq): Route =
    onComplete(Future {
      logger.debug(s"Receive message from edaAdapter. Length ${response.head.length}")
      scalaxb.fromXML[Envelope](response.head)
    }.flatMap(e => XmlParseHandler.reponseEbMsMessage(e))) {
      case Success(x) =>
        val notification = PontonRoute.notificationFor(x)
        if (notification.protocol == PontonRoute.ErrorProtocol)
          logger.error(s"Inbound message from edaAdapter not processed (conversation ${x.conversationId}, receiver ${x.receiver}): ${notification.message.errorMessage.getOrElse("")}")
        mqttPublisher ! MqttPublish(notification :: Nil)
        complete(HttpResponse(StatusCodes.NoContent))
      case Failure(ex) =>
        // The message does not parse, or a known document cannot be mapped (e.g. a new
        // MessageCode): keep the 500 so Ponton holds it and redelivers it after a fix
        // (team decision, platform#111 ca11). Only unknown document types are
        // acknowledged above, because eda-xp can never process them.
        logger.error("Error while parsing message from edaAdapter", ex)
        mqttPublisher ! MqttPublishError("NotSpecified", ex.getMessage)
        complete(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
    }

  val pontonRoutes: Route = withoutSizeLimit {
    pathPrefix("pontonxp") {
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
            withoutSizeLimit {
              if (forwardTargets.isEmpty)
                // no community forwarded: exactly the previous route (no read deadline, no size cap)
                entity(as[NodeSeq])(processLocally)
              else
                // strict, so the unchanged body can be forwarded after reading the receiver; no size
                // cap (the default max-to-strict-bytes of 8 MiB would reject large energy data), but
                // every message, forwarded or not, must arrive within StrictEntityTimeout
                toStrictEntity(PontonRoute.StrictEntityTimeout, Long.MaxValue) {
                  extractRequest { request =>
                    entity(as[NodeSeq]) { response =>
                      val receiver = InboundForwarding.receiverOf(response)
                      val alreadyForwarded = request.headers.exists(_.is(InboundForwarding.ForwardedHeader.toLowerCase))
                      InboundForwarding.decide(forwardTargets, receiver, alreadyForwarded) match {
                        case InboundForwarding.Local => processLocally(response)
                        case InboundForwarding.Loop(rc, target) =>
                          logger.error(s"Inbound message for $rc arrived already forwarded, but $rc is forwarded again to $target here; refusing it to avoid a loop")
                          complete(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
                        case InboundForwarding.Forward(rc, target) =>
                          // No MqttPublishError on failure: this zone's backend does not own the
                          // community any more; the error stays in this log and Ponton redelivers.
                          val started = System.nanoTime()
                          onComplete(forward(target, request)) { result =>
                            val ms = (System.nanoTime() - started) / 1000000
                            result match {
                              case Success(status) if status.isSuccess() =>
                                logger.info(s"Inbound message for $rc forwarded to $target: $status in $ms ms")
                                complete(HttpResponse(StatusCodes.NoContent))
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
        }
      )
    }
  }
}

object PontonRoute {
  val ErrorProtocol = "ERROR"

  /**
   * Reading the body (StrictEntityTimeout), parsing it and forwarding (ForwardTimeout) normally stay
   * below the server's request-timeout (pekko default 20 s, counted from the start of the request),
   * so the forward result decides the answer. A very large, slowly read body can still run into the
   * server's own 503; Ponton then redelivers as well (at-least-once). Ponton's EDA add-on waits 30 s
   * (eda.adapter.httpclient_timeout).
   */
  val StrictEntityTimeout: FiniteDuration = 5.seconds
  val ForwardTimeout: FiniteDuration = 10.seconds

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
