package at.energydash.actors.http

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import at.energydash.actors.MqttPublisher.{EdaNotification, MqttCommand, MqttPublish, MqttPublishError}
import at.energydash.domain.XmlParseHandler
import at.energydash.domain.eda.MessageHelper.EDAMessageCodeToProcessCode
import org.slf4j.{Logger, LoggerFactory}
import soapenvelope11.Envelope

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.{Failure, Success}

class PontonRoute(mqttPublisher: ActorRef[MqttCommand])(implicit val system: ActorSystem[_]) {

  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = system.scheduler
  implicit val ec: ExecutionContext = system.executionContext

  var logger: Logger = LoggerFactory.getLogger(this.getClass)

  logger.debug("Start Ponton Routes ...")

  val pontonRoutes: Route =
    pathPrefix("pontonxp") {
      concat(
        path("notification") {
          post {
            extractRequest { request =>
              println("Notification")
              println(request.headers)
              request.entity.toStrict(1 second).onComplete {
                case Success(d) => println(d.data.utf8String)
                case Failure(f) => println(f)
              }
              complete(HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
            }
          }
        } ~
          path("status") {
            post {
              extractRequest { request =>
                println("Status")
                println(request.headers)
                request.entity.toStrict(1 second).onComplete {
                  case Success(d) => println(d.data.utf8String)
                  case Failure(f) => println(f)
                }
                complete(HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
              }
            }
          } ~
          path("message") {
            post {
              extractRequest { request =>
                logger.debug(s"KEP-Server Message. {}", request.headers)
                request.entity.toStrict(1 second).map(_.data.utf8String) map { s =>
                  try {
                    val response = scala.xml.XML.loadString(s)
                    scalaxb.fromXML[Envelope](response)
                  }
                  catch {
                    case e: Exception => logger.error(e.toString + ": " + s)
                  }
                } onComplete  {
                  case Success(e:Envelope) => {
                    XmlParseHandler.reponseEbMsMessage(e).onComplete {
                      case Success(x) => mqttPublisher ! MqttPublish(EdaNotification(EDAMessageCodeToProcessCode(x.messageCode).toString, x) :: Nil)
                      case Failure(ex) => mqttPublisher ! MqttPublishError("NotSpecified", ex.getMessage)
                    }
                    complete(HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
                  }
                  case Failure(_) => complete(HttpResponse(StatusCodes.BadRequest, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
                }
                complete(HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
              }
            }
          }
      )
    }

}
