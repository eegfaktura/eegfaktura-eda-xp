package at.energydash.actors

import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import at.energydash.actors.MqttPublisher.MqttCommand
import at.energydash.actors.TenantMailActor.{DeleteEmailCommand, FetchEmailCommand}
import at.energydash.config.Config
import at.energydash.domain.EbMsMessage
import at.energydash.domain.dao.{SlickEmailOutboxRepository, TenantConfig}
import at.energydash.domain.eda.MessageHelper
import at.energydash.domain.eda.MessageHelper.EDAMessageCodeToProcessCode
import at.energydash.domain.enums.EbMsProcessType
import at.energydash.mailer.EmailService
import at.energydash.mailer.EmailService.SendEmailCommand
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration, MILLISECONDS}
import scala.language.implicitConversions

trait EmailCommand

class FetchMailTenantWorker(timers: TimerScheduler[EdaCommand],
                            tenant: TenantConfig,
                            mqttPublisher: ActorRef[MqttCommand],
                            mailRepo: SlickEmailOutboxRepository) {
  import FetchMailTenantWorker._
  implicit def asFiniteDuration(d: java.time.Duration): FiniteDuration   =
    scala.concurrent.duration.Duration.fromNanos(d.toNanos)

  var logger: Logger = LoggerFactory.getLogger(classOf[FetchMailTenantWorker])

  val rand = new scala.util.Random
  val interval: FiniteDuration = Config.interval(tenant.domain)

  private def setup(): Behavior[EdaCommand] = {
    Behaviors.setup { context => {
      context.log.info("Setup Tenant Worker")
      context.log.info(s"Interval: ${interval.toMillis}")

      timers.startTimerWithFixedDelay(TimerKey, Refresh, interval + Duration(rand.nextLong(interval.toMillis) / 2, MILLISECONDS))
      val mailActor = context.spawn(TenantMailActor(tenant, mailRepo), name = "mail-actor")

      def activated(mailActor: ActorRef[EmailCommand]): Behavior[EdaCommand] = {
        context.log.info(s"Activate Tenant Worker for tenant ${tenant.tenant}")
        Behaviors.receiveMessage[EdaCommand] {
          case Refresh =>
            mailActor ! FetchEmailCommand(tenant.tenant, "", mqttPublisher)
            Behaviors.same

          case WaitResponse =>
            mailActor ! FetchEmailCommand(tenant.tenant, "", mqttPublisher)
            Behaviors.same

          case SendEdaCommand(message, replyTo) =>
            val email = prepareEmail(message)
            context.log.debug(s"Forward mail to Mail Actor ${email.toEmail}")

            mailActor ! SendEmailCommand(email, tenant.domain, replyTo)
            timers.startSingleTimer(Refresh, 1.minute)

            Behaviors.same

          case msg: DeleteEmailCommand =>
            mailActor ! msg
            Behaviors.same
        }
      }

      activated(mailActor)
    }}
  }

  private def buildHeader(data: EbMsMessage) = {
    val msgCode = EDAMessageCodeToProcessCode(data.messageCode)
    val msgCodeVersion: Option[String] = msgCode match {
      case EbMsProcessType.PROCESS_EC_PRTFACT_CHANGE => Some("_01.00")
      case _ if data.messageCodeVersion.isDefined => data.messageCodeVersion.map("_" + _)
      case _ => None
    }
    s"[${msgCode}${
      msgCodeVersion match {
        case Some(v) => v
        case None => ""
      }
    } MessageId=${data.messageId.getOrElse("")}]"
  }

  private def prepareEmail(data: EbMsMessage) = MessageHelper.getEdaMessageByType(data).toByte.fold(
    e => throw e,
    attachment => {
      val subject = buildHeader(data)
      val to = data.receiver.toUpperCase()
      val tenant = data.sender.toUpperCase()

      logger.debug(s"Prepare Email Message Flow: $data")
      EmailService.EmailModel(tenant = tenant, toEmail = to,
        subject = subject, attachment = attachment, data = data)
    }
  )
}

object FetchMailTenantWorker {

  private case object TimerKey

  private case object Refresh extends EdaCommand
  private case object WaitResponse extends EdaCommand
//  case class EmitSendEmailCommand(email: EmailModel, replyTo: ActorRef[EmailCommand]) extends EdaCommand

  def apply(tenantConfig: TenantConfig,
            mqttPublisher: ActorRef[MqttCommand],
            mailRepo: SlickEmailOutboxRepository): Behavior[EdaCommand] = {
    Behaviors.withTimers(timers => new FetchMailTenantWorker(timers, tenantConfig, mqttPublisher, mailRepo).setup())
  }
}