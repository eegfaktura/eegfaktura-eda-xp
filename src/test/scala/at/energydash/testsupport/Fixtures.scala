package at.energydash.testsupport

import scala.io.Source
import scala.util.Using
import scala.xml.{Elem, XML}

/** Test resources under `src/test/resources`. */
object Fixtures {
  def text(path: String): String = Using.resource(Source.fromResource(path))(_.mkString)

  def xml(path: String): Elem = XML.loadString(text(path))

  /** A SOAP request as the Ponton messenger posts it to `/pontonxp/message` (`InboundDocument`, see
   *  `src/test/resources/Response-Envelope.xml`) around one EDA document. */
  def inboundEnvelope(doc: Elem, sender: String, receiver: String, messageType: String,
                      conversationId: String = "RC100401209901010000000000000002", version: String = "01.00"): String =
    s"""<SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"><SOAP-ENV:Header/><SOAP-ENV:Body>
       |<v320:InboundDocument xmlns:v320="http://xp.ponton.de/eda/v320"><v320:Header>
       |<v320:MessageId>MSG0000000001@test</v320:MessageId><v320:ConversationId>$conversationId</v320:ConversationId>
       |<v320:SenderId>$sender</v320:SenderId><v320:ReceiverId>$receiver</v320:ReceiverId>
       |<v320:MessageType>$messageType</v320:MessageType><v320:MessageVersion>$version</v320:MessageVersion></v320:Header>
       |<v320:Message>$doc</v320:Message></v320:InboundDocument></SOAP-ENV:Body></SOAP-ENV:Envelope>""".stripMargin

  /** Routing sender and receiver of an ebUtilities document. */
  def routing(doc: Elem): (String, String) =
    ((doc \\ "Sender" \ "MessageAddress").text.trim, (doc \\ "Receiver" \ "MessageAddress").text.trim)

  /** The ebUtilities document in a fixture: the fixture itself or the first element of an ebUtilities
   *  namespace inside it (a fixture may be a whole SOAP envelope); `None` for other documents. */
  def ebUtilitiesDocument(root: Elem): Option[Elem] =
    (root +: root.descendant.collect { case e: Elem => e })
      .find(e => Option(e.namespace).exists(_.startsWith("http://www.ebutilities.at/")))
}
