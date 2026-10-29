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
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.util.{Failure, Success, Try}

class TenantProvider(mqttPublisher: ActorRef[MqttCommand], queuedRequestLimit: FiniteDuration) {

  import TenantProvider._
  var logger: Logger = LoggerFactory.getLogger(classOf[TenantProvider])

  def start: Behavior[EdaCommand] = Behaviors.withStash[EdaCommand](StashCapacity) { buffer => Behaviors.withTimers[EdaCommand] { timers => Behaviors.setup[EdaCommand] { context => {

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

    // suffix of the mail worker names (261005-ca3): a replaced worker stops asynchronously after
    // GracefulShutdown, so its name may still be taken when the new one is spawned
    var workerSeq = 0

    def spawnMailWorker(t: TenantConfig): ActorRef[EdaCommand] = {
      workerSeq += 1
      context.spawn(
        Behaviors.supervise(
          FetchMailTenantWorker(t, mqttPublisher, mailRepo)
        ).onFailure[Exception](SupervisorStrategy.restart),
        s"worker-${t.tenant}-$workerSeq"
      )
    }

    // a replaced or reloaded mail worker must stop, or two workers poll the same mailbox (261005-ca3)
    def shutdownWorker(a: ActorRef[EdaCommand]): Unit =
      if (a.compareTo(pontonMessager) != 0) {
        try {
          a ! GracefulShutdown
        } catch {
          case e: Exception =>
            logger.warn(s"Error sending shutdown to tenant actor: ${e.getMessage}")
        }
      }

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
          waitingForInitialization()

        // after a supervisor restart requests arrive before the TenantStart the provider sends itself (261005-ca3)
        case msg @ (_: PassEdaCommand | _: QueuedPass | _: AddTenant | _: UpdateTenant) =>
          logger.debug(s"Queuing ${msg.getClass.getSimpleName} (initialization not started yet)")
          queue(msg)

        case other =>
          logger.warn(s"Received message ${other.getClass.getSimpleName} before initialization started")
          Behaviors.same
      }
    }

    // Queue a message until initialization is complete; replayed in arrival order by unstashAll
    def queue(msg: EdaCommand): Behavior[EdaCommand] = {
      if (buffer.isFull) {
        logger.warn(s"Discarding message ${msg.getClass.getSimpleName} during initialization, queue full (${buffer.capacity})")
      } else {
        msg match {
          // with the time it was queued: the caller waits PassAskTimeout, after that it must not be sent
          case p: PassEdaCommand => buffer.stash(QueuedPass(p, System.nanoTime()))
          case other => buffer.stash(other)
        }
      }
      Behaviors.same
    }

    // Behavior while waiting for initialization to complete
    def waitingForInitialization(): Behavior[EdaCommand] = {
      Behaviors.receiveMessage {
        case InitializationSuccess(tenantConfigs) =>
          val tenantActors = tenantConfigs.flatMap { t =>
            t.cType.toUpperCase match {

              case "KEP" =>
                Some(t.tenant.toUpperCase -> pontonMessager)

              case "MAIL" =>
                // a tenant whose worker cannot be spawned (e.g. a character not allowed in an actor name) is
                // left out instead of failing the provider and losing the queued messages (261005-ca3)
                Try(spawnMailWorker(t)) match {
                  case Success(mailWorker) => Some(t.tenant.toUpperCase -> mailWorker)
                  case Failure(e) =>
                    logger.error(s"Tenant ${t.tenant}: mail worker not started: ${e.getMessage}", e)
                    None
                }

              case _ =>
                logger.warn(s"Tenant ${t.tenant} has no type definition.")
                None
            }
          }.toMap

          logger.info(s"Initialization completed with ${tenantActors.size} tenants, ${buffer.size} queued messages")

          // Process queued messages in arrival order and transition to provide state
          buffer.unstashAll(provide(tenantActors))

        case InitializationFailure(exception) =>
          logger.error(
            s"Initialization failed: ${exception.getMessage}. Retrying in 5 seconds...",
            exception
          )
          // a timer, not scheduleOnce: a restart cancels it, so no stale TenantStart reaches the next incarnation
          timers.startSingleTimer(TenantStart, 5.seconds)
          // Keep queuing messages
          Behaviors.same

        // The retry scheduled above: load again, keep queuing (was discarded below, the provider never left this state)
        case TenantStart =>
          logger.info("Retrying tenant initialization")
          context.pipeToSelf(initializeTenants()) {
            case Success(Success(configs)) =>
              InitializationSuccess(configs)
            case Success(Failure(e)) =>
              InitializationFailure(e)
            case Failure(e) =>
              InitializationFailure(e)
          }
          Behaviors.same

        case msg: PassEdaCommand =>
          logger.debug(s"Queuing message for tenant ${msg.tenant} (initialization in progress)")
          queue(msg)

        case msg: QueuedPass =>
          queue(msg)

        case msg: AddTenant =>
          logger.debug("Queuing AddTenant message (initialization in progress)")
          queue(msg)

        case msg: UpdateTenant =>
          logger.debug("Queuing UpdateTenant message (initialization in progress)")
          queue(msg)

        case msg =>
          logger.warn(s"Discarding message ${msg.getClass.getSimpleName} during initialization")
          Behaviors.same
      }
    }

    // Pass a request to the tenant's worker
    def pass(tenantActors: Map[String, ActorRef[EdaCommand]], cmd: PassEdaCommand): Unit = {
      val PassEdaCommand(tenant, message, replyTo) = cmd
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
    }

    // Provided behavior - normal operation
    def provide(tenantActors: Map[String, ActorRef[EdaCommand]]): Behavior[EdaCommand] = {
      logger.info(s"Tenant Actor ready with tenants: ${tenantActors.keys.mkString(", ")}")

      Behaviors.receiveMessage {
        case cmd: PassEdaCommand =>
          pass(tenantActors, cmd)
          Behaviors.same

        // a queued request whose caller has given up (ask timeout) is answered with an error, never sent (261005-ca3)
        case QueuedPass(cmd, queuedAt) =>
          val waited = (System.nanoTime() - queuedAt).nanos
          if (waited > queuedRequestLimit) {
            logger.warn(s"Request for tenant ${cmd.tenant} (${cmd.message.messageCode}) queued ${waited.toSeconds} s during the tenant initialization: not sent")
            cmd.replyTo ! SendResponseError(cmd.tenant, cmd.message.receiver, "Request expired during the tenant initialization")
          } else pass(tenantActors, cmd)
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

          // Recreate tenant with updated configuration first: a worker that cannot be spawned (e.g. a character
          // not allowed in an actor name) is answered with an error instead of failing the provider after the
          // caller was told it worked (261005-ca3)
          val updated: Try[ActorRef[EdaCommand]] = tenantConfig.cType.toUpperCase match {
            case "KEP" =>
              Success(pontonMessager)
            case _ =>
              Try(spawnMailWorker(tenantConfig))
          }

          updated match {
            case Failure(e) =>
              logger.error(s"Tenant ${tenantConfig.tenant}: worker not started: ${e.getMessage}", e)
              replyTo ! ResponseError(s"Tenant worker not started: ${e.getMessage}")
              Behaviors.same

            case Success(updatedActor) =>
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
              tenantActors.get(tenantConfig.tenant.toUpperCase()).foreach(shutdownWorker)

              provide(tenantActors + (tenantConfig.tenant.toUpperCase() -> updatedActor))
          }

        case TenantStart =>
          // Reinitialize if needed; the reload spawns new mail workers, so the current ones stop
          logger.warn("Restarting tenant initialization")
          tenantActors.values.foreach(shutdownWorker)
          context.pipeToSelf(initializeTenants()) {
            case Success(Success(updatedTenants)) =>
              InitializationSuccess(updatedTenants)
            case Success(Failure(e)) =>
              InitializationFailure(e)
            case Failure(e) =>
              InitializationFailure(e)
          }
          waitingForInitialization()

        case msg: InitializationSuccess =>
          logger.warn(s"Received InitializationSuccess during normal operation, ignoring")
          Behaviors.same

        case msg: InitializationFailure =>
          logger.warn(s"Received InitializationFailure during normal operation, ignoring")
          Behaviors.same
      }
    }

    initializing()
  }}}}
}

object TenantProvider {

  // messages queued while the tenants are loaded; more are discarded with a warning
  private val StashCapacity = 1000

  // ===== Public Messages =====
  case object TenantStart extends EdaCommand

  // ===== Internal Messages =====
  private case class InitializationSuccess(tenantActors: Seq[TenantConfig])
    extends EdaCommand

  private case class InitializationFailure(exception: Throwable)
    extends EdaCommand

  // a request queued during the initialization, with the time (System.nanoTime) it was queued
  private case class QueuedPass(cmd: PassEdaCommand, queuedAt: Long) extends EdaCommand

  /** How long the request stream waits for the answer to a PassEdaCommand. */
  val PassAskTimeout: FiniteDuration = 30.seconds

  /** A queued request older than this is not sent any more: its caller gives up after PassAskTimeout (261005-ca3). */
  val QueuedRequestLimit: FiniteDuration = PassAskTimeout - 5.seconds

  def apply(mqttPublisher: ActorRef[MqttCommand], queuedRequestLimit: FiniteDuration = QueuedRequestLimit): Behavior[EdaCommand] =
    new TenantProvider(mqttPublisher, queuedRequestLimit).start

  /** The provider as the service runs it (261005-ca3): restarted with backoff when it fails, and it starts its
   *  own initialisation, so a restart loads the tenants again. `provider` is replaceable for tests. */
  def supervised(mqttPublisher: ActorRef[MqttCommand],
                 provider: ActorRef[MqttCommand] => Behavior[EdaCommand] = p => apply(p)): Behavior[EdaCommand] =
    Behaviors.supervise(Behaviors.setup[EdaCommand] { context =>
      context.self ! TenantStart
      provider(mqttPublisher)
    }).onFailure[Exception](SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2))
}