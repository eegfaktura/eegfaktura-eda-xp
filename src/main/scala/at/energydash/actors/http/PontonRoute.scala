package at.energydash.actors.http

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import at.energydash.actors.MqttPublisher.{EdaNotification, MqttCommand, MqttPublish, MqttPublishError}
import at.energydash.domain.{EbMsMessage, XmlParseHandler}
import at.energydash.domain.eda.MessageHelper.EDAMessageCodeToProcessCode
import org.slf4j.{Logger, LoggerFactory}
import soapenvelope11.Envelope

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
import scala.xml.NodeSeq

class PontonRoute(mqttPublisher: ActorRef[MqttCommand])(implicit val system: ActorSystem[_]) {

  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = system.scheduler
  implicit val ec: ExecutionContext = system.executionContext

  var logger: Logger = LoggerFactory.getLogger(this.getClass)

  logger.debug("Start Ponton Routes ...")

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
              entity(as[NodeSeq]) { response =>
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
                    // Envelope, Ponton header or document does not parse against the schema (incl. a
                    // SOAP Fault): keep the 500 and let Ponton hold the message. Parsed documents
                    // eda-xp cannot map are handled in XmlParseHandler.mapDataRecordToEbmsOrError.
                    logger.error("Error while parsing message from edaAdapter", ex)
                    mqttPublisher ! MqttPublishError("NotSpecified", ex.getMessage)
                    complete(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
                  case _ =>
                    logger.error(s"Undefined Error while parsing message from edaAdapter ")
                    complete(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
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
