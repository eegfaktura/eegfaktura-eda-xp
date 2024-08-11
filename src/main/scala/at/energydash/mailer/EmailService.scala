package at.energydash.mailer

import akka.actor.typed.ActorRef
import akka.util.ByteString
import at.energydash.actors.{EdaCommand, EmailCommand}
import at.energydash.domain.EbMsMessage
import courier.{Envelope, Mailer, Multipart}
import org.slf4j.{Logger, LoggerFactory}

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.mail.internet.InternetAddress
import scala.concurrent.{ExecutionContext, Future}

object EmailService {
  var logger: Logger = LoggerFactory.getLogger("EmailService")

  case class EmailModel(tenant: String, toEmail: String, subject: String, attachment: ByteString, data: EbMsMessage)

  case class AdminEmailModel(tenant: String, toEmail: String, subject: String, attachement: ByteString)
//  sealed trait Command

  case class SendEmailCommand(email: EmailModel, domain: String, replyTo: ActorRef[EdaCommand]) extends EmailCommand {

    def sendEmail(mailer:  Mailer)(implicit ex: ExecutionContext): Future[Unit] = {
      shippingEmail(mailer)
    }

    private def shippingEmail(mailer: Mailer)(implicit ex: ExecutionContext): Future[Unit] = {
      logger.info(s"About to send Email ${email.tenant}@${domain} to ${email.toEmail}@${domain}")
      try {
        val myFormatObj = DateTimeFormatter.ofPattern("yyyyMMdd")

        val envelope = Envelope
          .from(new InternetAddress(s"${email.tenant}@${domain}"))
          .to(new InternetAddress(s"${email.toEmail}@${domain}"))
          .subject(email.subject)
          .content(Multipart()
            .attachBytes(email.attachment.toArray,
              s"${LocalDateTime.now().format(myFormatObj)}_${email.data.messageCode.toString}_${email.data.sender}.xml", "text/xml")
            .html(s"Attachment for Process ${email.data.messageCode.toString}"))
        mailer(envelope)
      } catch {
        case e:Throwable => Future.failed(e)
      }
    }
  }

//  case class SendEmailResponse(email: EbMsMessage) extends EmailCommand
//
//  case class SendErrorResponse(tenant: String, message: String) extends EmailCommand
}