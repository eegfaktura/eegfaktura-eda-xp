package at.energydash.actors

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import org.apache.pekko.pki.pem.{DERPrivateKeyLoader, PEMDecoder}
import org.apache.pekko.util.Timeout
import at.energydash.actors.TenantProvider.TenantStart
import at.energydash.actors.http.{PontonRoute, ServiceRoute}
import at.energydash.config.Config
import at.energydash.mqtt.MqttSystem
import at.energydash.service.FileService
import at.energydash.stream.MqttRequestStream
import org.slf4j.{Logger, LoggerFactory}

import java.security.cert.{Certificate, CertificateFactory}
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt
import scala.io.Source
import scala.util.{Failure, Success}

object SupervisorActor {

  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  private def startHttpServer(routes: Route)(implicit system: ActorSystem[_]): Unit = {
    // Akka HTTP still needs a classic ActorSystem to start
    import system.executionContext

    val serverConfig = Config.serverConfig
    // Pekko-http 1.x: HTTP/2 is opt-in per binding (.enableHttps + ALPN, or
    // explicit .http2()); .bind() defaults to HTTP/1.1, sodass der frueher
    // explizite withHttp2Enabled(false)-Override nicht mehr noetig ist.
    val futureBinding = Http().newServerAt(serverConfig.host, serverConfig.port)/*.enableHttps(serverHttpContext)*/
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

    private def serverHttpContext: HttpsConnectionContext = {
      val privateKey =
        DERPrivateKeyLoader.load(PEMDecoder.decode(readPrivateKeyPem()))
      val fact = CertificateFactory.getInstance("X.509")
      val cer = fact.generateCertificate(
        classOf[AdminServer].getResourceAsStream("/certs/xpadapter-prod.pem")
      )
      val ks = KeyStore.getInstance("PKCS12")
      ks.load(null)
      ks.setKeyEntry(
        "private",
        privateKey,
        new Array[Char](0),
        Array[Certificate](cer)
      )
      val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
      keyManagerFactory.init(ks, null)
      val context = SSLContext.getInstance("TLS")
      context.init(keyManagerFactory.getKeyManagers, null, new SecureRandom)
      ConnectionContext.httpsServer(context)
    }

    private def readPrivateKeyPem(): String =
      Source.fromResource("certs/xpadapter-prod.key").mkString

  def apply(): Behavior[Command] =
    Behaviors.setup { implicit ctx =>
      implicit val system: ActorSystem[Nothing] = ctx.system
      implicit val timeout: Timeout = Timeout(5.seconds)
      implicit val ex: ExecutionContextExecutor = system.executionContext

      ctx.setLoggerName("Supervisor")
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
          case m =>
          ctx.log.warn(s"Supervisor: Can not handle message -> ${m}")
            Behaviors.same
        }
      process()
    }
}
