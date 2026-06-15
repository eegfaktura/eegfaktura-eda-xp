package at.energydash.domain.eda

import org.apache.pekko.util.ByteString
import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.domain.enums.EbMsMessageType.EbMsMessageType
import scalaxb.{CanWriteXML, DataRecord}

import java.io.StringWriter
import java.text.SimpleDateFormat
import scala.language.postfixOps
import scala.util.Try
import scala.xml._
import scala.xml.transform.RewriteRule

trait EdaMessage {
  val message: EbMsMessage

  def getVersion(version: Option[String]): Try[EdaXMLMessage[_]]
}


trait EdaXMLMessage[EDAType] {

  val message: EbMsMessage

  def rootNodeLabel: Option[String] = None

  def schemaLocation: Option[String] = None

  //  implicit val edaType: EDAType = edaType

  implicit val edaTypeCanWrite: CanWriteXML[EDAType]

  val dateFmt = new SimpleDateFormat("yyyy-MM-dd")

  def toScope: NamespaceBinding

  def toRecord: DataRecord[EDAType] = DataRecord[EDAType](schemaLocation, rootNodeLabel, schemaLocation, None, toDoc)

  def toDoc: EDAType

  def toXML: Node

  def toByte: Try[ByteString] = Try {
    val xmlString = new StringWriter()
    val xml = if (rootNodeLabel.isDefined && schemaLocation.isDefined) {
      rewriteRootSchema(toXML, rootNodeLabel.get, schemaLocation.get)
    } else {
      toXML
    }
    //    XML.save(s"Portfolio.xml", xml, "UTF-8", true, null)
    XML.write(xmlString, xml, "UTF-8", true, null)
    ByteString.fromString(xmlString.toString)
  }

  def rewriteRootSchema(xml: Node, rootNodeLabel: String, schemaLocaction: String): Node = {
    val schemaLoc = new PrefixedAttribute("xsi", "schemaLocation", schemaLocaction, Null)

    xml.asInstanceOf[Elem] % schemaLoc

    //    val setSchemaAndNamespaceRule = new NamespaceAndSchema(rootNodeLabel, schemaLoc)
    //    new RuleTransformer(setSchemaAndNamespaceRule).transform(xml).head
  }
}

trait EdaResponseType {
  def fromXML(xmlFile: Elem): Try[EdaMessage]

  def resolveMessageCode(xmlFile: Elem): Try[EbMsMessageType] = {
    Try(EbMsMessageType.withName(xmlFile \\ "MessageCode" text))
  }
}


// new class that extends RewriteRule
class NamespaceAndSchema(rootLabel: String, attrs: MetaData) extends RewriteRule {
  // create a RewriteRule that sets this as the only namespace
  override def transform(n: Node): Seq[Node] = n match {

    // ultimately, it's just a matter of setting the scope & attributes
    // on a new copy of the xml node
    case e: Elem if (e.label == rootLabel) =>
      e.copy(attributes = e.attributes.append(attrs))
    case n =>
      n
  }
}

class MessageCodeExtractor() {

  def fromXML(xmlFile: Elem) = {

  }

}