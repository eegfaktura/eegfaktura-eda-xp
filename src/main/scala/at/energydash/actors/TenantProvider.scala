package at.energydash.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import at.energydash.actors.MqttPublisher.{MqttCommand, MqttPublishCommand}
import at.energydash.domain.dao.{Db, SlickEmailOutboxRepository, SlickTenantConfigRepository}
import at.energydash.mailer.EmailService.EmailModel
import at.energydash.mqtt.CommandMessage
import io.circe.parser._
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

class TenantProvider(mqttPublisher: ActorRef[MqttCommand]) {

  import TenantProvider._
  var logger: Logger = LoggerFactory.getLogger(classOf[TenantProvider])

  def start: Behavior[EdaCommand] = Behaviors.setup[EdaCommand] { context => {

    import context.executionContext

    val dbConfig = Db.getConfig
    val mailRepo = new SlickEmailOutboxRepository(dbConfig)
    val tenantConfigRepository = new SlickTenantConfigRepository(dbConfig)
    //    tenantConfigRepository.init()

    val pontonMessager = context.spawn(PontonService(), name = "worker-ponton-messenager")

    def setup(): Behavior[EdaCommand] = {
      Behaviors.receiveMessage {
        case TenantStart =>
          logger.info("Start Tenant Actor")
          val mailTenants = Await.result(tenantConfigRepository.allActivated(), 3.seconds)
          val a = mailTenants.map(t => t.domain.toUpperCase match {
            case "KEP" => (t.tenant.toUpperCase() -> pontonMessager)
            case _ => (t.tenant.toUpperCase() -> context.spawn(FetchMailTenantWorker(t, mqttPublisher, mailRepo), s"worker-${t.tenant}"))
          }).toMap
          provide(a)
      }
    }

    def provide(tenantActors: Map[String, ActorRef[EdaCommand]]): Behavior[EdaCommand] = {
      logger.info(s"Start Tenant Actor with teanants ${tenantActors}")
      Behaviors.receiveMessage {
        case PassEdaCommand(tenant, message, replyTo) =>
          tenantActors.get(tenant.toUpperCase()) match {
            case Some(a) => a ! SendEdaCommand(message, replyTo)
            case None => replyTo ! SendResponseError(tenant, "Tenant not registered")
          }
          Behaviors.same
//        case DeleteMail(tenant, messageId) =>
//          tenantActors.get(tenant) match {
//            case Some(a) => a ! DeleteEmailCommand(tenant, messageId)
//            case None =>
//          }
//          Behaviors.same
        case AddTenant(tenantConfig, replyTo) =>
          tenantConfigRepository.create(tenantConfig).onComplete {
            case Success(_) =>
              context.self ! TenantAdded(tenantConfig, replyTo)
            case Failure(e) =>
              replyTo ! ResponseError(e.getMessage)
          }
          Behaviors.same

        case TenantAdded(tenantConfig, replyTo) =>
          replyTo ! ResponseOk

          parse(s"""{"online": true}""") match {
            case Right(json) => mqttPublisher ! MqttPublishCommand(CommandMessage(tenantConfig.tenant, "pontonOnlineState", json))
            case Left(e) => logger.error(s"Register Tenant: ${e.message}")
          }

          provide(
            tenantActors +
              (tenantConfig.tenant -> context.spawn(FetchMailTenantWorker(tenantConfig, mqttPublisher, mailRepo), s"worker-${tenantConfig.tenant}")))

      }
    }
    setup()
  }}
}

object TenantProvider {

  case object TenantStart extends EdaCommand

  case class DistributeMail(tenant: String, mail: EmailModel, replyTo: ActorRef[EdaCommand]) extends EdaCommand

//  case class DeleteMail(tenant: String, messageId: String) extends EdaCommand

  def apply(mqttPublisher: ActorRef[MqttCommand]): Behavior[EdaCommand] =
    new TenantProvider(mqttPublisher).start
}
