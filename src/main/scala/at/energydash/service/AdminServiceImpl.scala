package at.energydash.service

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, Scheduler}
import akka.util.Timeout
import at.energydash.actors.{AddTenant, EdaCommand, ResponseError, ResponseOk}
import at.energydash.admin.{RegisterPontonRequest, RegisterPontonService, RegisteredPontonReply}
import at.energydash.domain.dao.TenantConfig

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class AdminServiceImpl(actorRef: ActorRef[EdaCommand])(implicit val sch: Scheduler, ec: ExecutionContext) extends RegisterPontonService {

  implicit val timeout: Timeout = Timeout(10.seconds)
  /**
   * Sends a greeting
   */
  override def register(in: RegisterPontonRequest): Future[RegisteredPontonReply] = {

    val tenantConfig = TenantConfig(tenant = in.tenant,
      domain = in.domain, host = s"mail.${in.domain}", imapPort = 143,
      smtpHost = s"mail.${in.domain}", smtpPort = 25,
      user = in.tenant.toLowerCase, passwd = in.password, imapSecurity = "STARTTLS", smtpSecurity ="STARTTLS", active = true)

    actorRef.ask(ref => AddTenant(tenantConfig, ref)).transform {
      case Success(res) => res match {
        case ResponseOk => Try(RegisteredPontonReply(200, "OK"))
        case ResponseError(msg) => Try(RegisteredPontonReply(500, msg))
      }
      case Failure(e) =>
        Try(RegisteredPontonReply(500, e.getMessage))
    }
  }
}
