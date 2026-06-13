package at.energydash.actors

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import at.energydash.actors.FetchMailTenantWorker.GracefulShutdown
import at.energydash.actors.MqttPublisher.{MqttCommand, MqttPublishCommand}
import at.energydash.config.Config
import at.energydash.domain.dao.{Db, SlickEmailOutboxRepository, SlickTenantConfigRepository, TenantConfig}
import at.energydash.mqtt.CommandMessage
import io.circe.parser._
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

class TenantProvider(mqttPublisher: ActorRef[MqttCommand]) {

  import TenantProvider._
  var logger: Logger = LoggerFactory.getLogger(classOf[TenantProvider])

  def start: Behavior[EdaCommand] = Behaviors.setup[EdaCommand] { context => {

    import context.executionContext

    val dbConfig = Db.getConfig
    val mailRepo = new SlickEmailOutboxRepository(dbConfig)
    val tenantConfigRepository = new SlickTenantConfigRepository(dbConfig)

    // Create PontonService with supervision strategy
    val pontonMessager = context.spawn(
      Behaviors.supervise(PontonService())
        .onFailure[Exception](SupervisorStrategy.restart),
      name = "worker-ponton-messenager"
    )

    // Async initialization: Load tenants without blocking
//    def initializeTenants(): Future[Try[Map[String, ActorRef[EdaCommand]]]] = {
//      tenantConfigRepository.allActivated(Config.superviseType)
//        .map { kepTenants =>
//          Try {
//            kepTenants.map(t => t.cType.toUpperCase match {
//              case "KEP" => Some(t.tenant.toUpperCase() -> pontonMessager)
//              case "MAIL" =>
//                val mailWorker = context.spawn(
//                  Behaviors.supervise(FetchMailTenantWorker(t, mqttPublisher, mailRepo))
//                    .onFailure[Exception](SupervisorStrategy.restart),
//                  s"worker-${t.tenant}"
//                )
//                Some(t.tenant.toUpperCase() -> mailWorker)
//              case _ =>
//                logger.warn(s"Tenant ${t.tenant} has no type definition.")
//                None
//            }).filter(_.isDefined).map(_.get).toMap
//          }
//        }
//        .recover { case e =>
//          logger.error(s"Failed to load tenant configuration: ${e.getMessage}", e)
//          Failure(e)
//        }
//    }

    def initializeTenants(): Future[Try[Seq[TenantConfig]]] = {
      tenantConfigRepository.allActivated(Config.superviseType)
        .map(Success(_))
        .recover { case e =>
          logger.error(s"Failed to load tenant configuration: ${e.getMessage}", e)
          Failure(e)
        }
    }

    // Queue for messages while initializing
    def initializing(): Behavior[EdaCommand] = {
      Behaviors.receiveMessage {
        case TenantStart =>
          logger.info(s"Start Tenant Actor for type ${Config.superviseType.getOrElse("All")}")

          // Use pipeToSelf to handle async result without blocking
          context.pipeToSelf(initializeTenants()) {
            case Success(Success(configs)) =>
              InitializationSuccess(configs)
            case Success(Failure(e)) =>
              InitializationFailure(e)
            case Failure(e) =>
              InitializationFailure(e)
          }

          // Return waiting behavior that queues incoming messages
          waitingForInitialization(messageQueue = Vector.empty)

        case other =>
          logger.warn(s"Received message ${other.getClass.getSimpleName} before initialization started")
          Behaviors.same
      }
    }

    // Behavior while waiting for initialization to complete
    def waitingForInitialization(messageQueue: Vector[EdaCommand]): Behavior[EdaCommand] = {
      Behaviors.receiveMessage {
        case InitializationSuccess(tenantConfigs) =>
          val tenantActors = tenantConfigs.flatMap { t =>
            t.cType.toUpperCase match {

              case "KEP" =>
                Some(t.tenant.toUpperCase -> pontonMessager)

              case "MAIL" =>
                val mailWorker = context.spawn(
                  Behaviors.supervise(
                    FetchMailTenantWorker(t, mqttPublisher, mailRepo)
                  ).onFailure[Exception](SupervisorStrategy.restart),
                  s"worker-${t.tenant}"
                )
                Some(t.tenant.toUpperCase -> mailWorker)

              case _ =>
                logger.warn(s"Tenant ${t.tenant} has no type definition.")
                None
            }
          }.toMap

          logger.info(s"Initialization completed with ${tenantActors.size} tenants")

          // Process queued messages and transition to provide state
          val provideBehavior = provide(tenantActors)
          messageQueue.foldLeft(provideBehavior) { (behavior, msg) =>
            // Reprocess queued messages
            behavior
          }
          provideBehavior

        case InitializationFailure(exception) =>
          logger.error(
            s"Initialization failed: ${exception.getMessage}. Retrying in 5 seconds...",
            exception
          )
          context.scheduleOnce(5.seconds, context.self, TenantStart)
          // Keep queuing messages
          Behaviors.same

        case msg: PassEdaCommand =>
          logger.debug(s"Queuing message for tenant ${msg.tenant} (initialization in progress)")
          waitingForInitialization(messageQueue :+ msg)

        case msg: AddTenant =>
          logger.debug("Queuing AddTenant message (initialization in progress)")
          waitingForInitialization(messageQueue :+ msg)

        case msg: UpdateTenant =>
          logger.debug("Queuing UpdateTenant message (initialization in progress)")
          waitingForInitialization(messageQueue :+ msg)

        case msg =>
          logger.warn(s"Discarding message ${msg.getClass.getSimpleName} during initialization")
          Behaviors.same
      }
    }

    // Provided behavior - normal operation
    def provide(tenantActors: Map[String, ActorRef[EdaCommand]]): Behavior[EdaCommand] = {
      logger.info(s"Tenant Actor ready with tenants: ${tenantActors.keys.mkString(", ")}")

      Behaviors.receiveMessage {
        case PassEdaCommand(tenant, message, replyTo) =>
          try {
            tenantActors.get(tenant.toUpperCase()) match {
              case Some(a) => a ! SendEdaCommand(message, replyTo)
              case None =>
                logger.warn(s"Tenant not registered: $tenant")
                replyTo ! SendResponseError(tenant, message.receiver, "Tenant not registered")
            }
          } catch {
            case e: Exception =>
              logger.error(s"Error passing command to tenant: ${e.getMessage}", e)
              replyTo ! SendResponseError(tenant, message.receiver, s"Internal error: ${e.getMessage}")
          }
          Behaviors.same

        case AddTenant(tenantConfig, replyTo) =>
          logger.info(s"Adding tenant: ${tenantConfig.tenant}")
          tenantConfigRepository.create(tenantConfig).onComplete {
            case Success(_) =>
              context.self ! TenantModified(tenantConfig, replyTo)
            case Failure(e) =>
              logger.error(s"Failed to add tenant ${tenantConfig.tenant}: ${e.getMessage}", e)
              replyTo ! ResponseError(e.getMessage)
          }
          Behaviors.same

        case UpdateTenant(tenantConfig, replyTo) =>
          logger.info(s"Updating tenant: ${tenantConfig.tenant}")
          tenantConfigRepository.update(tenantConfig).onComplete {
            case Success(_) =>
              context.self ! TenantModified(tenantConfig, replyTo)
            case Failure(e) =>
              logger.error(s"Update Tenant failed for ${tenantConfig.tenant}: ${e.getMessage}", e)
              replyTo ! ResponseError(e.getMessage)
          }
          Behaviors.same

        case TenantModified(tenantConfig, replyTo) =>
          logger.info(s"Tenant modified: ${tenantConfig.tenant}")
          replyTo ! ResponseOk()

          // Publish tenant state with error handling
          parse(s"""{"online": true}""") match {
            case Right(json) =>
              mqttPublisher ! MqttPublishCommand(
                CommandMessage(tenantConfig.tenant, "pontonOnlineState", json)
              )
            case Left(e) =>
              logger.error(
                s"Failed to parse MQTT message for tenant ${tenantConfig.tenant}: ${e.message}"
              )
          }

          // Gracefully shutdown old actor reference
          tenantActors.get(tenantConfig.tenant.toUpperCase()).foreach(a => {
            if (a.compareTo(pontonMessager) != 0) {
              try {
                a ! GracefulShutdown
              } catch {
                case e: Exception =>
                  logger.warn(s"Error sending shutdown to tenant actor: ${e.getMessage}")
              }
            }
          })

          // Recreate tenant with updated configuration
          val updatedActor = tenantConfig.cType.toUpperCase match {
            case "KEP" =>
              pontonMessager
            case _ =>
              context.spawn(
                Behaviors.supervise(
                  FetchMailTenantWorker(tenantConfig, mqttPublisher, mailRepo)
                ).onFailure[Exception](SupervisorStrategy.restart),
                s"worker-${tenantConfig.tenant}"
              )
          }

          provide(tenantActors + (tenantConfig.tenant.toUpperCase() -> updatedActor))

        case TenantStart =>
          // Reinitialize if needed
          logger.warn("Restarting tenant initialization")
          context.pipeToSelf(initializeTenants()) {
            case Success(Success(updatedTenants)) =>
              InitializationSuccess(updatedTenants)
            case Success(Failure(e)) =>
              InitializationFailure(e)
            case Failure(e) =>
              InitializationFailure(e)
          }
          waitingForInitialization(messageQueue = Vector.empty)

        case msg: InitializationSuccess =>
          logger.warn(s"Received InitializationSuccess during normal operation, ignoring")
          Behaviors.same

        case msg: InitializationFailure =>
          logger.warn(s"Received InitializationFailure during normal operation, ignoring")
          Behaviors.same
      }
    }

    initializing()
  }}
}

object TenantProvider {

  // ===== Public Messages =====
  case object TenantStart extends EdaCommand

  // ===== Internal Messages =====
  private case class InitializationSuccess(tenantActors: Seq[TenantConfig])
    extends EdaCommand

  private case class InitializationFailure(exception: Throwable)
    extends EdaCommand

  def apply(mqttPublisher: ActorRef[MqttCommand]): Behavior[EdaCommand] =
    new TenantProvider(mqttPublisher).start
}