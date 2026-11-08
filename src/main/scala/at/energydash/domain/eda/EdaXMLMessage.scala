package at.energydash.domain.eda

import org.apache.pekko.util.ByteString
import at.energydash.domain.EbMsMessage
import org.slf4j.{Logger, LoggerFactory}
import scalaxb.{CanWriteXML, DataRecord}

import java.io.StringWriter
import scala.util.Try
import scala.xml._

trait EdaMessage {
  val message: EbMsMessage

  def getVersion(version: Option[String]): Try[EdaXMLMessage[_]]

  /**
   * getVersion for a missing version label or one without its own case: the request is NOT
   * built. Until 1.0.7 an outdated schema was sent instead (the messenger then rejects it, or
   * header and body disagree).
   */
  protected def fallbackVersion(): Try[EdaXMLMessage[_]] = {
    val msg = s"Unknown version label ${message.messageCodeVersion.getOrElse("<none>")} for ${message.messageCode} " +
      s"(conversationId=${message.conversationId}): request not sent. " +
      "Check eda-process-versions in the backend config (schema set version, e.g. ANFORDERUNG_ECON 02.40)."
    EdaMessage.logger.error(msg)
    scala.util.Failure(new IllegalArgumentException(msg))
  }
}

object EdaMessage {
  private val logger: Logger = LoggerFactory.getLogger(classOf[EdaMessage])
}


trait EdaXMLMessage[EDAType] {

  val message: EbMsMessage

  def rootNodeLabel: Option[String] = None

  def schemaLocation: Option[String] = None

  //  implicit val edaType: EDAType = edaType

  implicit val edaTypeCanWrite: CanWriteXML[EDAType]

  def toScope: NamespaceBinding

  def toRecord: DataRecord[EDAType] = DataRecord[EDAType](schemaLocation, rootNodeLabel, schemaLocation, None, toDoc)

  def toDoc: EDAType

  def toXML: Node

  def toByte: Try[ByteString] = Try {
    val xmlString = new StringWriter()
    val xml = if (rootNodeLabel.isDefined && schemaLocation.isDefined) {
      rewriteRootSchema(toXML, schemaLocation.get)
    } else {
      toXML
    }
    //    XML.save(s"Portfolio.xml", xml, "UTF-8", true, null)
    XML.write(xmlString, xml, "UTF-8", true, null)
    ByteString.fromString(xmlString.toString)
  }

  def rewriteRootSchema(xml: Node, schemaLocaction: String): Node = {
    val schemaLoc = new PrefixedAttribute("xsi", "schemaLocation", schemaLocaction, Null)

    xml.asInstanceOf[Elem] % schemaLoc
  }
}
