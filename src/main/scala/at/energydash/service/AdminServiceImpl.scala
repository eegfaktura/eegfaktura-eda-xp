package at.energydash.service

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, Scheduler}
import akka.util.Timeout
import at.energydash.actors._
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
    val tenantConfig = TenantConfig(tenant = in.tenant, cType = in.pontonCommType,
      domain = Some(in.domain), host = Some(s"mail.${in.domain}"), imapPort = Some(143),
      smtpHost = Some(s"mail.${in.domain}"), smtpPort = Some(25),
      user = Some(in.tenant.toLowerCase), passwd = Some(in.password), imapSecurity = Some("STARTTLS"), smtpSecurity = Some("STARTTLS"), active = true)

    actorRef.ask(ref => UpdateTenant(tenantConfig, ref)).transform {
      case Success(res) => res match {
        case ResponseOk() => Try(RegisteredPontonReply(200, "OK"))
        case ResponseError(msg) => Try(RegisteredPontonReply(500, msg))
      }
      case Failure(e) =>
        Try(RegisteredPontonReply(500, e.getMessage))
    }
  }

  override def update(in: RegisterPontonRequest): Future[RegisteredPontonReply] = {
    val tenantConfig = TenantConfig(tenant = in.tenant, in.pontonCommType,
      domain = Some(in.domain), host = Some(s"mail.${in.domain}"), imapPort = Some(143),
      smtpHost = Some(s"mail.${in.domain}"), smtpPort = Some(25),
      user = Some(in.tenant.toLowerCase), passwd = Some(in.password), imapSecurity = Some("STARTTLS"), smtpSecurity = Some("STARTTLS"), active = true)

    actorRef.ask(ref => UpdateTenant(tenantConfig, ref)).transform {
      case Success(res) => res match {
        case ResponseOk() => Try(RegisteredPontonReply(200, "OK"))
        case ResponseError(msg) => Try(RegisteredPontonReply(500, msg))
      }
      case Failure(e) =>
        Try(RegisteredPontonReply(500, e.getMessage))
    }
  }
}
