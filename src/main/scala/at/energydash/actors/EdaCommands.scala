package at.energydash.actors

import org.apache.pekko.actor.typed.ActorRef
import at.energydash.domain.EbMsMessage
import at.energydash.domain.dao.TenantConfig

trait EdaCommand

case class AddTenant(tenant: TenantConfig, replyTo: ActorRef[EdaCommand]) extends EdaCommand
case class UpdateTenant(tenant: TenantConfig, replyTo: ActorRef[EdaCommand]) extends EdaCommand

case class TenantModified(tenant: TenantConfig, replyTo: ActorRef[EdaCommand]) extends EdaCommand

case class ResponseError(msg: String) extends EdaCommand
case class SendResponseError(tenant: String, receiver: String, message: String, step: String = "") extends EdaCommand

case class ResponseOk() extends EdaCommand

case class PassEdaCommand(tenant: String, message: EbMsMessage, replyTo: ActorRef[EdaCommand]) extends EdaCommand

case class SendEdaCommand(message: EbMsMessage, replyTo: ActorRef[EdaCommand]) extends EdaCommand
//case class TestSendEdaCommand(message: EbMsMessage) extends EdaCommand

case class SendEdaResponse(email: EbMsMessage) extends EdaCommand

//case class SendErrorResponse(tenant: String, message: String, step: String = "") extends EdaCommand

case class ReceiveEdaRequest(tenant: String, message: EbMsMessage) extends EdaCommand


