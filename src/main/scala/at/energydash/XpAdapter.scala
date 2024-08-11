package at.energydash

import akka.actor.typed.ActorSystem
import at.energydash.actors.{Start, SupervisorActor}
import ponton.{OutHeaderType, Message2}
import scalaxb.DataRecord

object XpAdapter extends App {

//  printSendDocument()

  val supervisor = ActorSystem(SupervisorActor(), "supervisor")
  supervisor ! Start


  def printSendDocument(): Unit = {

    import scalaxb.XMLStandardTypes._

    val header = OutHeaderType(MessageId = "messageId", SenderId = "senderId", ReceiverId = "receiverId", MessageVersion = "07.00", MessageType = "messageType", LogInfo = Some("LogInfo"))
    val message = Message2(
      message2option = DataRecord[String]("Daten")
    )
    def baseAddress = new java.net.URI("http://localhost/outboundDocument")
    def defaultScope = scalaxb.toScope(Some("oe0") -> "http://www.ebutilities.at/datenplattform/0700",
      Some("oe") -> "http://xp.ponton.de/eda/v320",
      Some("tns") -> "http://xp.ponton.de/eda/v320",
      Some("xs") -> "http://www.w3.org/2001/XMLSchema",
      Some("xsi") -> "http://www.w3.org/2001/XMLSchema-instance")

    var node = scalaxb.toXML(ponton.OutboundDocument(header, message), Some("http://xp.ponton.de/eda/v320"), "OutboundDocument", defaultScope)
    println(node)
  }
}
