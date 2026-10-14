package at.energydash.testsupport

import scala.io.Source
import scala.util.Using
import scala.xml.{Elem, XML}

/** Test resources under `src/test/resources`. */
object Fixtures {
  def text(path: String): String = Using.resource(Source.fromResource(path))(_.mkString)

  def xml(path: String): Elem = XML.loadString(text(path))

  /** The ebUtilities document in a fixture: the fixture itself or the first element of an ebUtilities
   *  namespace inside it (a fixture may be a whole SOAP envelope); `None` for other documents. */
  def ebUtilitiesDocument(root: Elem): Option[Elem] =
    (root +: root.descendant.collect { case e: Elem => e })
      .find(e => Option(e.namespace).exists(_.startsWith("http://www.ebutilities.at/")))
}
