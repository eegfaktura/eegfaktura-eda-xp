package at.energydash.actors

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.Timeout
import at.energydash.actors.MqttPublisher.{EdaNotification, MqttCommand, MqttPublish}
import at.energydash.domain.dao.{SlickEmailOutboxRepository, TenantConfig}
import at.energydash.domain.eda.EdaErrorMessage
import at.energydash.mailer.EmailService.SendEmailCommand
import at.energydash.mailer.Fetcher.{ErrorMessage, FetcherContext, MailContent, MailMessage}
import at.energydash.mailer.{ConfiguredMailer, Fetcher}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}


class TenantMailActor(tenantConfig: TenantConfig, mailRepo: SlickEmailOutboxRepository) {

  import TenantMailActor._

  implicit val timeout: Timeout = Timeout(5.seconds)
  var logger: Logger = LoggerFactory.getLogger(classOf[TenantMailActor])

  var tenant: String = tenantConfig.tenant
//  val config: com.typesafe.config.Config = Config.getMailSessionConfig(tenant)
  private val mailSession = ConfiguredMailer.getSession(tenantConfig)

  implicit val mailContext: FetcherContext = FetcherContext(tenant, mailSession, mailRepo)

  def start: Behavior[EmailCommand] = Behaviors.setup[EmailCommand] { context => {
    import context.{executionContext, system}

    context.setLoggerName(TenantMailActor.getClass)

    def distributeMail(req: FetchEmailCommand)(mail: MailContent) = {
        mail match {
          case m: MailMessage => req.replyTo ! MqttPublish(EdaNotification(m.protocol, m.content) :: Nil)
          case ErrorMessage(_, protocol, content) =>
            val c = EdaErrorMessage(content.copy(receiver = tenant))
            req.replyTo ! MqttPublish(EdaNotification(protocol = protocol, message = c.message) :: Nil)
        }
    }

    def work(): Behavior[EmailCommand] = {
      Behaviors.withMdc(Map("tenant" -> tenant)) {
        Behaviors.receiveMessage {
          case req: FetchEmailCommand =>
            req.fetchEmail(req.subject, distributeMail(req))
            Behaviors.same
          case req: SendEmailCommand =>
            context.log.info(s"Send Mail to ${req.email.toEmail}")
            req.sendEmail(ConfiguredMailer.createMailerFromSession(mailSession)).onComplete {
              case Success(_) =>
                logger.info(s"Sent Mail to ${req.email.toEmail}")
                req.replyTo ! SendEdaResponse(req.email.data)
              case Failure(ex) =>
                req.replyTo ! SendResponseError(tenant, req.email.toEmail, ex.getMessage, "Send Mail")
            }
            Behaviors.same
          case req: DeleteEmailCommand =>
            req.deleteMail()
            Behaviors.same
        }
      }
    }
    work()
  }}
}

object TenantMailActor {
  case class FetchEmailCommand(tenant: String, subject: String, replyTo: ActorRef[MqttCommand]) extends EmailCommand {
    def fetchEmail(searchTerm: String, distributeMessage: MailContent => Unit)(implicit ex: ExecutionContext, ctx: FetcherContext): Unit /*Try[List[MailContent]]*/ = {
      Try(
        Fetcher().fetch(searchTerm, distributeMessage)
      )
    }
  }

  case class DeleteEmailCommand(tenant: String, messageId: String) extends EmailCommand {
    def deleteMail()(implicit ctx: FetcherContext): Unit = {
      Fetcher().deleteById(messageId)
    }
  }

  def apply(tenantConfig: TenantConfig, mailRepo: SlickEmailOutboxRepository): Behavior[EmailCommand] = {
    new TenantMailActor(tenantConfig, mailRepo).start
  }
}
