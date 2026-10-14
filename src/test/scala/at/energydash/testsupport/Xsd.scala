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

  def schema(file: String): Schema = cache.computeIfAbsent(file, f => {
    val path = new File(Dir, f)
    require(path.isFile, s"no XSD $path")
    SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(path)
  })

  /** Throws `org.xml.sax.SAXParseException` with line/column when the document is invalid. */
  def validate(node: Node, file: String): Unit =
    schema(file).newValidator().validate(new StreamSource(new StringReader(node.toString)))
}
