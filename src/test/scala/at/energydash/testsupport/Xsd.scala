package at.energydash.testsupport

import java.io.{File, StringReader}
import java.util.concurrent.ConcurrentHashMap
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.{Schema, SchemaFactory}
import scala.xml.Node

/**
 * Validation against the official ebUtilities / Ponton XSDs in `src/main/xsd` (read as files from the
 * project root, the forked test JVM's working directory, so relative `xs:import`s resolve).
 */
object Xsd {
  val Dir = new File("src/main/xsd")
  private val cache = new ConcurrentHashMap[String, Schema]()
  // The JDK's secure-processing limit (5 000 content-model nodes) refuses the official ECMPList 01p10 XSD;
  // the schemas here are the vendor files of the repository, so the limit is lifted for the tests only.
  System.setProperty("jdk.xml.maxOccurLimit", "0")

  def schema(file: String): Schema = cache.computeIfAbsent(file, f => {
    val path = new File(Dir, f)
    require(path.isFile, s"no XSD $path")
    SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(path)
  })

  /** XSD file name per `targetNamespace` of every schema in `src/main/xsd`. */
  lazy val byNamespace: Map[String, String] =
    Dir.listFiles().toList.filter(_.getName.endsWith(".xsd")).flatMap { f =>
      val root = scala.xml.XML.loadFile(f)
      Option(root \@ "targetNamespace").filter(_.nonEmpty).map(_ -> f.getName)
    }.toMap

  def forNamespace(ns: String): String = byNamespace.getOrElse(ns, sys.error(s"no XSD for namespace $ns"))

  /** Throws `org.xml.sax.SAXParseException` with line/column when the document is invalid. */
  def validate(node: Node, file: String): Unit = {
    val text = node.toString
    try schema(file).newValidator().validate(new StreamSource(new StringReader(text)))
    catch {
      case e: org.xml.sax.SAXParseException =>
        throw new org.xml.sax.SAXParseException(s"${e.getMessage} — against $file:\n${text.take(1500)}", null, e)
    }
  }
}
