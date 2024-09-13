package at.energydash.actors

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import at.energydash.actors.http.AkkaHttpHandler
import at.energydash.actors.soap.PontonRequest
import at.energydash.domain.eda.MessageHelper

import scala.util.{Failure, Success}


trait PontonWebService {
  def pontonWebService(ctx: ActorContext[_]) = (new PontonRequest(ctx.system) with AkkaHttpHandler).service
}

class PontonService(context: ActorContext[EdaCommand]) extends AbstractBehavior[EdaCommand](context) { this: PontonWebService =>

  import context._
  private val service: PontonRequest#OutboundDocument4SOAPBinding = pontonWebService(context)

  override def onMessage(msg: EdaCommand): Behavior[EdaCommand] = {
    msg match {
      case SendEdaCommand(ebmsMessage, replyTo) =>
        service.sendRequest(ebmsMessage)(context.executionContext) onComplete {
//          case Success((header, body)) =>
//            println(s"Response From SendRequest: $header - $body")
          case Success(envelope) =>
            println(s"Response From SendRequest: $envelope")
            replyTo ! SendEdaResponse(ebmsMessage)
          case Failure(exception) => SendErrorResponse(ebmsMessage.sender, exception.getMessage)
        }
        Behaviors.same
      case TestSendEdaCommand(edaMessage) =>
        service.sendRequest(edaMessage)(context.executionContext)
        Behaviors.same

    }
  }
}

object PontonService {
  def buildMessageId(sender: String, seqId: Long) = s"MSG${MessageHelper.formatSeqNumber(seqId)}@${sender}"
  def apply(): Behavior[EdaCommand] = Behaviors.setup(context => new PontonService(context) with PontonWebService)
}
