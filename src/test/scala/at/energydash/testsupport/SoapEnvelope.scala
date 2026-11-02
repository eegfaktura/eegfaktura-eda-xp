package at.energydash.testsupport

import scala.xml.{Elem, Node}

/** Splits the SOAP request eda-xp sends to Ponton into the `OutboundDocument` header and the EDA document. */
object SoapEnvelope {
  private def outboundDocument(envelope: Elem): Node =
    (envelope \\ "OutboundDocument").headOption.getOrElse(sys.error(s"no OutboundDocument in ${envelope.label}"))

  def header(envelope: Elem): Map[String, String] =
    (outboundDocument(envelope) \ "Header").head.child.collect { case e: Elem => e.label -> e.text.trim }.toMap

  /** The EDA document inside `OutboundDocument/Message`. */
  def document(envelope: Elem): Elem =
    (outboundDocument(envelope) \ "Message").head.child.collectFirst { case e: Elem => e }
      .getOrElse(sys.error("empty OutboundDocument/Message"))
}
