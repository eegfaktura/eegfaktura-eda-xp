package at.energydash.actors

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.Http
import at.energydash.actors.http.{BodySizeLimit, InboundForwarding, PontonRoute, ServiceRoute}
import at.energydash.config.Config
import at.energydash.mqtt.MqttSystem
import at.energydash.service.FileService
import at.energydash.stream.MqttRequestStream
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Failure, Success}

object SupervisorActor {

  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  private def startHttpServer(routes: Route)(implicit system: ActorSystem[_]): Unit = {
    // Akka HTTP still needs a classic ActorSystem to start
    import system.executionContext

    val serverConfig = Config.serverConfig
    // pekko-http 1.x: HTTP/2-Flag ist nach PreviewServerSettings gewandert
    // (ServerSettings.withHttp2Enabled gibt's nicht mehr direkt). Globales
    // pekko.http.server.preview.enable-http2 = on in reference.conf aktiviert
    // h2c fuer .bind(); hier ueberschreiben wir per-Binding auf off, damit
    // der Admin-REST-Server reines HTTP/1.1 spricht.
    val futureBinding = Http().newServerAt(serverConfig.host, serverConfig.port)
      .adaptSettings(_.mapPreviewServerSettings(_.withEnableHttp2(false)))
      .bind(routes)
    futureBinding.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        logger.info("Server online at http://{}:{}/", address.getHostString, address.getPort)
      case Failure(ex) =>
        system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }
  }

  private def startGRPCServer(tenantProvider: ActorRef[EdaCommand])(implicit system: ActorSystem[_]) = {
    AdminServer.apply(tenantProvider, system)
  }

  def apply(): Behavior[Command] =
    Behaviors.setup { implicit ctx =>
      implicit val system: ActorSystem[Nothing] = ctx.system

      ctx.setLoggerName("Supervisor")
//      val messageStore = ctx.spawn(MessageStorage(), name = "message-storage")
      val conversationEntity = ctx.spawn(ConversationEntity(), name = "conversationentity")

      val ebMsAggregator = ctx.spawn(EbMsAggregator(conversationEntity), name = "ebMsAggregator")

      val mqttSystem = ctx.spawn(MqttSystem(Config.getMqttConfig()), name = "mqtt-system")
      val mqttPublisher = ctx.spawn(MqttPublisher(mqttSystem, ebMsAggregator), name = "mqtt-publisher")

      val messageTransformer = ctx.spawn(PrepareMessageActor(PrepareMessageActor.seqFloor(Config.config)), "message-transformer")
      val tenantProvider = ctx.spawn(TenantProvider.supervised(mqttPublisher), name = "tenant-provider")

      val mqttRequestStream = MqttRequestStream(tenantProvider, messageTransformer, conversationEntity)

      val targets = InboundForwarding.targets(Config.config)
      val maxBodyBytes = BodySizeLimit.maxBytes(Config.config)
      // deadlines only matter (and are only checked) when something is forwarded
      val routes =
        if (targets.isEmpty) new PontonRoute(mqttPublisher, maxBodyBytes = maxBodyBytes)
        else {
          val deadlines = InboundForwarding.timeouts(Config.config)
          new PontonRoute(mqttPublisher, targets, forwardTimeout = deadlines.forward, readTimeout = deadlines.read, maxBodyBytes = maxBodyBytes)
        }
      val fileService = FileService(system, mqttPublisher)
      val adminRoutes = new ServiceRoute(fileService, maxBodyBytes)
      //      val pontonPublisher = ctx.spawn(PontonService(), name = "ponton-service")

      def process(): Behavior[Command] =
        Behaviors.receiveMessage {
          case Start =>
//            adapter.partnerExistenceTest()
//            adapter.getPartnerlistTest()
//            adapter.sendTest()
            mqttRequestStream.startCommand()

            startHttpServer(routes.pontonRoutes ~ adminRoutes.adminRoutes)
            startGRPCServer(tenantProvider)

//            val edaMessage = EbMsMessage(
//              conversationId = "AT003000202303041506076450000003761",
//              requestId = Some("5JWLV5Z3"),
//              messageId = Some("RC100181202303041506080740000003762"),
//              sender = "RC100130", receiver = "AT003000", messageCode = EbMsMessageType.ONLINE_REG_INIT, messageCodeVersion = Some("02.00"),
//              meter = Some(Meter("AT0030000000000000000000000655856", Some(MeterDirectionType.CONSUMPTION))), ecId = Some("AT00300000000RC100181000000956509"))
//            pontonPublisher ! TestSendEdaCommand(edaMessage)
            Behaviors.same
          case Shutdown =>
            Behaviors.same
          case m =>
          ctx.log.warn(s"Supervisor: Can not handle message -> ${m}")
            Behaviors.same
        }
      process()
    }
}
