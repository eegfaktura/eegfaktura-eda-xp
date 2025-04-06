package at.energydash.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import at.energydash.actors.FetchMailTenantWorker.GracefulShutdown
import at.energydash.actors.MqttPublisher.{MqttCommand, MqttPublishCommand}
import at.energydash.config.Config
import at.energydash.domain.dao.{Db, SlickEmailOutboxRepository, SlickTenantConfigRepository}
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

    val pontonMessager = context.spawn(PontonService(), name = "worker-ponton-messenager")

    def setup(): Behavior[EdaCommand] = {
      Behaviors.receiveMessage {
        case TenantStart =>
          logger.info(s"Start Tenant Actor for type ${Config.superviseType}")
          val kepTenants = Await.result(tenantConfigRepository.allActivated(Config.superviseType), 3.seconds)
          val a = kepTenants.map(t => t.cType.toUpperCase match {
            case "KEP" => Some(t.tenant.toUpperCase() -> pontonMessager)
            case "MAIL" => Some(t.tenant.toUpperCase() -> context.spawn(FetchMailTenantWorker(t, mqttPublisher, mailRepo), s"worker-${t.tenant}"))
            case _ =>
              logger.warn(s"Tenant ${t.tenant} has no type definition.")
              None
          }).filter(_.isDefined).map(_.get).toMap
          provide(a)
      }
    }

    def provide(tenantActors: Map[String, ActorRef[EdaCommand]]): Behavior[EdaCommand] = {
      logger.info(s"Start Tenant Actor with tenants ${tenantActors.keys}")
      Behaviors.receiveMessage {
        case PassEdaCommand(tenant, message, replyTo) =>
          tenantActors.get(tenant.toUpperCase()) match {
            case Some(a) => a ! SendEdaCommand(message, replyTo)
            case None => replyTo ! SendResponseError(tenant, message.receiver, "Tenant not registered")
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
              context.self ! TenantModified(tenantConfig, replyTo)
            case Failure(e) =>
              replyTo ! ResponseError(e.getMessage)
          }
          Behaviors.same

        case UpdateTenant(tenantConfig, replyTo) =>
          tenantConfigRepository.update(tenantConfig).onComplete {
            case Success(r) =>
              context.self ! TenantModified(tenantConfig, replyTo)
            case Failure(e) =>
              logger.error(s"Update Tenant: $e")
              replyTo ! ResponseError(e.getMessage)
          }
          Behaviors.same

        case TenantModified(tenantConfig, replyTo) =>
          replyTo ! ResponseOk()

          parse(s"""{"online": true}""") match {
            case Right(json) => mqttPublisher ! MqttPublishCommand(CommandMessage(tenantConfig.tenant, "pontonOnlineState", json))
            case Left(e) => logger.error(s"Register Tenant: ${e.message}")
          }

          tenantActors.get(tenantConfig.tenant.toUpperCase()).foreach(a => {
            if (a.compareTo(pontonMessager) != 0) {
              a ! GracefulShutdown
            }
          }
          )

          logger.info(s"Adapt tenant according to new configuration: $tenantConfig")
          tenantConfig.cType match {
            case "KEP" =>
              provide(tenantActors + (tenantConfig.tenant.toUpperCase() -> pontonMessager))
            case _ =>
              provide(
                tenantActors + (tenantConfig.tenant.toUpperCase() -> context.spawn(FetchMailTenantWorker(tenantConfig, mqttPublisher, mailRepo), s"worker-${tenantConfig.tenant}")))
          }
      }
    }
    setup()
  }}
}

object TenantProvider {

  case object TenantStart extends EdaCommand

//  case class DistributeMail(tenant: String, mail: EmailModel, replyTo: ActorRef[EdaCommand]) extends EdaCommand

  def apply(mqttPublisher: ActorRef[MqttCommand]): Behavior[EdaCommand] =
    new TenantProvider(mqttPublisher).start
}
