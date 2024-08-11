package at.energydash.actors

import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.grpc.scaladsl.ServiceHandler
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import at.energydash.admin.RegisterPontonServiceHandler
import at.energydash.admin.mail.SendMailServiceHandler
import at.energydash.config.Config
import at.energydash.mailer.ConfiguredMailer
import at.energydash.service.{AdminServiceImpl, SendMailServiceImpl}
import org.slf4j.LoggerFactory

import javax.mail.Session
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object AdminServer {

  private def adminSrvConfig = Config.adminSrvConfig
  def mailSession: Session = ConfiguredMailer.getAdminSession(adminSrvConfig)
  def apply(mailService: ActorRef[EdaCommand], system: ActorSystem[_]): Future[Http.ServerBinding] = new AdminServer(mailService, system).run()
}

class AdminServer(tenantProvider: ActorRef[EdaCommand], system: ActorSystem[_]) {
  val logger = LoggerFactory.getLogger(this.getClass)
  def run(): Future[Http.ServerBinding] = {
    implicit val sys: ActorSystem[_] = system
    implicit val ec: ExecutionContext = system.executionContext
    implicit val sch: Scheduler = system.scheduler

    val mailerService: PartialFunction[HttpRequest, Future[HttpResponse]] =
      SendMailServiceHandler.partial(new SendMailServiceImpl(AdminServer.mailSession))

    val adminService: PartialFunction[HttpRequest, Future[HttpResponse]] = {
      RegisterPontonServiceHandler.partial(new AdminServiceImpl(tenantProvider))
    }

    val services: HttpRequest => Future[HttpResponse] = ServiceHandler.concatOrNotFound(mailerService, adminService)
    val srvPort = AdminServer.adminSrvConfig.getConfig("grpc").getInt("port")

    val bound: Future[Http.ServerBinding] = Http(system)
      .newServerAt(interface = "0.0.0.0", port = srvPort)
//      .enableHttps(serverHttpContext)
      .bind(services)
      .map(_.addToCoordinatedShutdown(hardTerminationDeadline = 20.seconds))

    bound.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        logger.info("gRPC server bound to {}:{}", address.getHostString, address.getPort)
      case Failure(ex) =>
        logger.error("Failed to bind gRPC endpoint, terminating system", ex)
        system.terminate()
    }

    bound
  }
}
