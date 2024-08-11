package at.energydash.actors.http

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import at.energydash.config.Config
import org.slf4j.{Logger, LoggerFactory}
import scalaxb.HttpClientsAsync

import java.net.URI
import scala.concurrent.{ExecutionContext, Future}

trait AkkaHttpHandler extends AkkaHttpClients{
  def sendRequest(httpRequest: HttpRequest)(implicit actorSystem: ActorSystem[_]): Future[HttpResponse] = {
    Http().singleRequest(httpRequest)
  }
}

trait AkkaHttpClients extends HttpClientsAsync { this: AkkaHttpHandler =>
//  val http: HttpExt
  implicit def actorSystem: ActorSystem[_]
  implicit def ec: ExecutionContext

  val httpClient: AkkaHttpClient = new AkkaHttpClient {}

  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  val kepServerConfig = Config.edaKepServer
  val kepServerUrl = Uri(Config.edaKepServer.getString("url"))

  trait AkkaHttpClient extends HttpClient {
    override def request(in: String, address: URI, headers: Map[String, String])(implicit ec: ExecutionContext): Future[String] = {
      logger.debug(s"content: ${in}")
      logger.debug(s"headers: ${headers}")
      val r = HttpRequest(
        method = HttpMethods.POST,
//        uri = Uri("http://10.10.10.51:6060/ponton/eda/webservice/outbound"),
        uri = kepServerUrl,
        entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, in),
        headers = headers.filter(h => h._1 != "Content-Type").map(h => RawHeader(h._1, h._2)).toSeq
      )
      sendRequest(r).map(r => r.status match {
        case StatusCodes.OK => in
        case StatusCodes.Accepted => in
        case _ => throw new RuntimeException(s"Bad Response from EDA Adapter ${r.status}")
      })
    }
  }
}