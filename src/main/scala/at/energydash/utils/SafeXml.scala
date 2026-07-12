package at.energydash
package utils

import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/** XXE-hardened scala.xml loader for attacker-controlled XML.
  *
  * Inbound EDA messages are attacker-controlled (PONTON forwards XML
  * from network-operator partners, EDA-MAIL mode forwards attacker-
  * authored attachments). `scala.xml.XML.load*` uses a platform-default
  * SAXParserFactory which permits DOCTYPE declarations and external
  * entities, making the unhardened calls vulnerable to XXE
  * (CWE-611) and billion-laughs DoS (CWE-776).
  */
object SafeXml {

  /** Shared SAX parser factory with secure-processing + DOCTYPE/external-
    * entity disabled. Factory is configured once at class load.
    */
  private val factory: SAXParserFactory = {
    val f = SAXParserFactory.newInstance()
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    f.setFeature("http://xml.org/sax/features/external-general-entities", false)
    f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    f.setXIncludeAware(false)
    f.setNamespaceAware(true)
    f
  }

  /** scala.xml.XMLLoader configured with the hardened factory above. Use
    * this instead of `scala.xml.XML` for every XML input that crosses a
    * trust boundary.
    */
  val loader: scala.xml.factory.XMLLoader[scala.xml.Elem] =
    new scala.xml.factory.XMLLoader[scala.xml.Elem] {
      override def parser: javax.xml.parsers.SAXParser = factory.newSAXParser()
    }
}
