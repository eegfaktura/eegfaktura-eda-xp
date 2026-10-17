package at.energydash.actors.http

import org.apache.pekko.actor.typed.{ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.model.{Multipart, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import at.energydash.domain.EbMsMessage
import at.energydash.service.FileService
import com.github.pjfanning.pekkohttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.{DurationInt, FiniteDuration}

case class EcIdUpdateHttpMessage(conversationId: String, ecId: String)
case class ConversationMessage(conversationId: String, message: EbMsMessage)
case class ConversationMessages(conversations: Seq[ConversationMessage])

class ServiceRoute(fileService: FileService, maxBodyBytes: Long)(implicit val system: ActorSystem[_]) {

  import at.energydash.domain.JsonImplicit._

  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = system.scheduler
  implicit val ec: ExecutionContextExecutor = system.executionContext

  private val logger: Logger = LoggerFactory.getLogger(this.getClass)
  private val bodyLimit = new BodySizeLimit(maxBodyBytes, logger, "Admin upload")

  val adminRoutes: Route =
    pathPrefix("admin") {
      concat (
        path("upload") {
          post {
            // read completely first: an upload above the limit publishes none of its parts
            (bodyLimit.directive & toStrictEntity(ServiceRoute.UploadReadTimeout, maxBodyBytes)) {
              optionalHeaderValueByName("ecId") { ecId =>
                entity(as[Multipart.FormData]) { formData =>
                  onSuccess(fileService.handleUpload(formData, ecId)) { results =>
                    complete(StatusCodes.Created)
                  }
                }
              }
            }
          }
//        } ~
//          path("conversations") {
//            get {
//              println("Query Conversations")
//              val processFuture = conversationEntity.ask(ref => FindAll(ref)).mapTo[MessageStorage.MessageAllFound]
//              onSuccess(processFuture) { res =>
//                complete(res)
//              }
//            } ~
//              post {
//                entity(as[ConversationMessages]) { msg =>
//                  val processFuture = Future.sequence(msg.conversations.map(m => messageStore.ask(ref => UpdateMessage(m.message, ref)).mapTo[MessageStorage.UpdateMessageResult]))
//                  onSuccess(processFuture) {res => complete(res)}
//                }
//              } ~
//              put {
//                entity(as[EcIdUpdateHttpMessage]) { msg =>
//                  val updateEcIdFuture = messageStore.ask(ref => UpdateEcId(msg.conversationId, msg.ecId, ref)).mapTo[MessageStorage.UpdateMessageResult]
//                  onSuccess(updateEcIdFuture) {res => complete(res)}
//                }
//              }
          }
      )
    }

}

object ServiceRoute {
  /** Time to receive an upload completely; the server's request-timeout (20 s default) still applies. */
  val UploadReadTimeout: FiniteDuration = 60.seconds
}
