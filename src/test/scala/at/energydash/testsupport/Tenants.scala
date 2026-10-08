package at.energydash.testsupport

import at.energydash.actors.{EdaCommand, PassEdaCommand, SendResponseError}
import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.EbMsMessageType
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.Assertions

import scala.concurrent.duration._
import scala.util.Try

object Tenants extends Assertions {
  /** Waits until a `TenantProvider` left its initialisation: a command for an unknown tenant is answered
   *  (commands sent during the initialisation are dropped, 261005-ca4). */
  def awaitReady(provider: ActorRef[EdaCommand], kit: ActorTestKit): Unit = {
    val probe = kit.createTestProbe[EdaCommand]()
    val msg = EbMsMessage(conversationId = "C", sender = "not-a-tenant", receiver = "AT003000",
      messageCode = EbMsMessageType.ZP_LIST)
    val ready = Iterator.continually {
      provider ! PassEdaCommand("not-a-tenant", msg, probe.ref)
      Try(probe.expectMessageType[SendResponseError](200.millis)).isSuccess
    }.take(50).contains(true)
    assert(ready, "tenant provider did not finish its initialisation within 10 s")
  }
}
