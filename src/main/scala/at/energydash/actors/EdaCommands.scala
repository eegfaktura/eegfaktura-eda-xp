package at.energydash.actors

import akka.actor.typed.ActorRef
import at.energydash.domain.EbMsMessage
import at.energydash.domain.dao.TenantConfig

trait EdaCommand

case class AddTenant(tenant: TenantConfig, replyTo: ActorRef[EdaCommand]) extends EdaCommand

case class TenantAdded(tenant: TenantConfig, replyTo: ActorRef[EdaCommand]) extends EdaCommand

case class ResponseError(msg: String) extends EdaCommand
case class SendResponseError(tenant: String, message: String) extends EdaCommand

case object ResponseOk extends EdaCommand

case class PassEdaCommand(tenant: String, message: EbMsMessage, replyTo: ActorRef[EdaCommand]) extends EdaCommand

case class SendEdaCommand(message: EbMsMessage, replyTo: ActorRef[EdaCommand]) extends EdaCommand
case class TestSendEdaCommand(message: EbMsMessage) extends EdaCommand

case class SendEdaResponse(email: EbMsMessage) extends EdaCommand

case class SendErrorResponse(tenant: String, message: String) extends EdaCommand

case class ReceiveEdaRequest(tenant: String, message: EbMsMessage) extends EdaCommand


