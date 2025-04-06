package at.energydash.actors.http

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

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

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
                println("Notification")
                println(request.headers)
              }.flatMap(_=>request.entity.toStrict(1 second))) {
                case Success(d) =>
                  println(d.data.utf8String)
                  complete(HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
                case Failure(f) =>
                  println(f)
                  complete(HttpResponse(StatusCodes.BadRequest, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
              }
            }
          }
        } ~
          path("status") {
            post {
              extractRequest { request =>
                onComplete {
                  println("Status")
                  println(request.headers)
                  request.entity.toStrict(1 second)
                } {
                  case Success(d) =>
                    println(d.data.utf8String)
                    complete(HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
                  case Failure(e) =>
                    logger.error(e.toString)
                    complete(HttpResponse(StatusCodes.BadRequest, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
                }
              }
            }
          } ~
          path("message") {
//            post {
//                extractDataBytes { dataSource =>
//                  onSuccess(dataSource.runFold(ByteString.empty)(_.concat(_))) { bs =>
//                    println(bs.decodeString("UTF-8"))
//                    complete(StatusCodes.OK)
//                  }
//                }
              post {
                extractStrictEntity(60.seconds) { entity =>
                  onComplete(Future{
                    println(s"Message from EDA $entity")
                    logger.info(s"Receive message from edaAdapter. ${entity.contentLength}")
                    val response = scala.xml.XML.loadString(entity.data.utf8String)
                    scalaxb.fromXML[Envelope](response)
                  }.flatMap(e => XmlParseHandler.reponseEbMsMessage(e).map(Tuple2(_, entity.data)))) {
                    case Success((x,b)) =>
                      mqttPublisher ! MqttPublish(EdaNotification(EDAMessageCodeToProcessCode(x.messageCode).toString, x) :: Nil)
                      complete(HttpResponse(StatusCodes.NoContent))
                    case Failure(ex) =>
                      logger.error(s"Error while parsing message from edaAdapter {}", ex.getMessage)
                      mqttPublisher ! MqttPublishError("NotSpecified", ex.getMessage)
                      complete(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
                    case _ =>
                      logger.error(s"Undefined Error while parsing message from edaAdapter ")
                      complete(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
                  }
                }
              }
//                entity(as[HttpEntity]) { request =>
//                  logger.debug(s"KEP-Server Message. Header: {}", request)
//                  onComplete(request.dataBytes.runFold(new ByteStringBuilder)(_ append _)) {
//                    case Success(eventBytes) =>
//                      println(s"RECEIVE MESSAGE: ${eventBytes.result().utf8String}")
//                      val response = scala.xml.XML.loadString(eventBytes.result().utf8String)
//                      val e = scalaxb.fromXML[Envelope](response)
//                      XmlParseHandler.reponseEbMsMessage(e).onComplete {
//                        case Success(x) =>
//                          mqttPublisher ! MqttPublish(EdaNotification(EDAMessageCodeToProcessCode(x.messageCode).toString, x) :: Nil)
//                        case Failure(ex) =>
//                          logger.error(s"Error while parsing message from edaAdapter {}", ex.getMessage)
//                          mqttPublisher ! MqttPublishError("NotSpecified", ex.getMessage)
//                      }
//                      complete(StatusCodes.OK)
//                    case Failure(error) =>
//                      logger.error(s"Error while streaming message from edaAdapter {}", error.getMessage)
//                      complete(StatusCodes.InternalServerError)
//                  }
//                }
//                extractRequest { request =>
//                  println(s"RECEIVE edaAdapter Message: {}", request)
//                  onComplete(
//                    //                  logger.debug(s"KEP-Server Message. {}", request.headers)
//                    request.entity.toStrict(1 second).map(_.data.utf8String) map { s =>
//                      //                    try {
//                      //                      Try {
//                      val response = scala.xml.XML.loadString(s)
//                      println(s)
//                      scalaxb.fromXML[Envelope](response)
//                      //                      }
//                      //                    }
//                      //                    catch {
//                      //                      case e: Exception => logger.error(e.toString + ": " + s)
//                      //                    }
//                    } flatMap (e => XmlParseHandler.reponseEbMsMessage(e))
//                  ) {
//                    case Success(m: EbMsMessage) => {
//                      logger.info(s"KEP-Server Message. {}", m)
//                      //                    XmlParseHandler.reponseEbMsMessage(e).onComplete {
//                      //                      case Success(x) => mqttPublisher ! MqttPublish(EdaNotification(EDAMessageCodeToProcessCode(x.messageCode).toString, x) :: Nil)
//                      //                      case Failure(ex) => mqttPublisher ! MqttPublishError("NotSpecified", ex.getMessage)
//                      //                    }
//                      mqttPublisher ! MqttPublish(EdaNotification(EDAMessageCodeToProcessCode(m.messageCode).toString, m) :: Nil)
//                      complete(HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
//                    }
//                    case Failure(e) =>
//                      logger.error(e.toString)
//                      mqttPublisher ! MqttPublishError("NotSpecified", e.getMessage)
//                      complete(HttpResponse(StatusCodes.BadRequest, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
//                  }
//                }
//              }
          }
      )
    }
  }

}
