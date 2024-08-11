package at.energydash.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import at.energydash.actors.TenantProvider.TenantStart
import at.energydash.actors.http.{PontonRoute, ServiceRoute}
import at.energydash.config.Config
import at.energydash.mqtt.MqttSystem
import at.energydash.service.FileService
import at.energydash.stream.MqttRequestStream

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

//sealed trait Command
//case object Start extends Command
//case object Shutdown extends Command


object SupervisorActor {

  private def startHttpServer(routes: Route)(implicit system: ActorSystem[_]): Unit = {
    // Akka HTTP still needs a classic ActorSystem to start
    import system.executionContext

    val futureBinding = Http().newServerAt("localhost", 6090).bind(routes)
    futureBinding.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info("Server online at http://{}:{}/", address.getHostString, address.getPort)
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
      implicit val timeout: Timeout = Timeout(5.seconds)
      implicit val ex: ExecutionContextExecutor = system.executionContext

//      val messageStore = ctx.spawn(MessageStorage(), name = "message-storage")
      val conversationEntity = ctx.spawn(ConversationEntity(), name = "conversationentity")

      val ebMsAggregator = ctx.spawn(EbMsAggregator(conversationEntity), name = "ebMsAggregator")

      val mqttSystem = ctx.spawn(MqttSystem(Config.getMqttConfig()), name = "mqtt-system")
      val mqttPublisher = ctx.spawn(MqttPublisher(mqttSystem, ebMsAggregator), name = "mqtt-publisher")

      val messageTransformer = ctx.spawn(PrepareMessageActor(), "message-transformer")
      val tenantProvider = ctx.spawn(TenantProvider(mqttPublisher), name = "tenant-provider")

      val mqttRequestStream = MqttRequestStream(tenantProvider, messageTransformer, conversationEntity)

      val routes = new PontonRoute(mqttPublisher)
      val fileService = FileService(system, mqttPublisher)
      val adminRoutes = new ServiceRoute(fileService)
      //      val pontonPublisher = ctx.spawn(PontonService(), name = "ponton-service")

      def process(): Behavior[Command] =
        Behaviors.receiveMessage {
          case Start =>
//            adapter.partnerExistenceTest()
//            adapter.getPartnerlistTest()
//            adapter.sendTest()
            tenantProvider ! TenantStart
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
        }
      process()
    }
}
