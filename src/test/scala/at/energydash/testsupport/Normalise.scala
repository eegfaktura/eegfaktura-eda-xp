package at.energydash.testsupport

import java.time.{LocalDate, ZoneId}
import scala.xml._

/**
 * Canonical text of an outbound EDA document and its Ponton header for the golden files
 * (protocol-changes.md §3): values that vary per run become tokens, prefixes become stable aliases
 * derived from the namespace, attributes are sorted, whitespace is re-indented. Everything else stays literal.
 */
object Normalise {
  val Vienna: ZoneId = ZoneId.of("Europe/Vienna")

  private val idTokens = Map(
    "MessageId" -> "@MESSAGE_ID@", "ConversationId" -> "@CONVERSATION_ID@", "CMRequestId" -> "@REQUEST_ID@",
    "DocumentCreationDateTime" -> "@CREATED@")

  /** Date tokens for one point in time; a test computes them before and after the build (midnight). */
  def dateTokens(today: LocalDate = LocalDate.now(Vienna)): Map[String, String] =
    Map(today.toString -> "@TODAY@", today.plusDays(1).toString -> "@TOMORROW@")

  def document(doc: Elem, dates: Map[String, String]): String = {
    val uris = collectUris(doc)
    val aliases = alias(uris)
    val scope = aliases.toSeq.sortBy(_._2).foldRight(TopScope: NamespaceBinding) { case ((uri, p), s) =>
      NamespaceBinding(p, uri, s)
    }
    new PrettyPrinter(160, 2).format(rewrite(doc, doc.scope, aliases, scope, dates).head)
  }

  def header(h: Map[String, String]): String =
    h.toSeq.sortBy(_._1).map {
      case ("MessageId", _) => "MessageId=@HEADER_MESSAGE_ID@"
      case (k, v) => s"$k=$v"
    }.mkString("", "\n", "\n")

  private def collectUris(n: Node): List[String] = n match {
    case e: Elem =>
      val own = Option(e.namespace).toList ++ e.attributes.collect {
        case a: PrefixedAttribute => e.scope.getURI(a.pre)
      }.filter(_ != null)
      (own ++ e.child.flatMap(collectUris)).distinct
    case _ => Nil
  }

  private def alias(uris: List[String]): Map[String, String] = {
    val VersionSeg = "\\d\\dp\\d\\d".r
    uris.foldLeft(Map.empty[String, String]) { (m, uri) =>
      val segs = uri.split('/').filter(_.nonEmpty)
      val base = segs.reverse.find(s => VersionSeg.unapplySeq(s).isEmpty).getOrElse("ns")
        .replaceAll("[^A-Za-z]", "").toLowerCase match { case "" => "ns"; case b => b }
      val p = Iterator.from(1).map(i => if (i == 1) base else s"$base$i").find(c => !m.values.exists(_ == c)).get
      m + (uri -> p)
    }
  }

  private def rewrite(n: Node, origScope: NamespaceBinding, aliases: Map[String, String], scope: NamespaceBinding,
                      dates: Map[String, String]): Seq[Node] = n match {
    case e: Elem =>
      val attrs = e.attributes.toList.sortBy(_.key).foldRight(Null: MetaData) {
        case (a: PrefixedAttribute, rest) =>
          new PrefixedAttribute(aliases.getOrElse(e.scope.getURI(a.pre), a.pre), a.key, a.value.text, rest)
        case (a, rest) => new UnprefixedAttribute(a.key, a.value.text, rest)
      }
      val children: Seq[Node] =
        if (idTokens.contains(e.label) && e.child.forall(_.isAtom)) Seq(Text(idTokens(e.label)))
        else e.child.flatMap(c => rewrite(c, e.scope, aliases, scope, dates))
      Elem(Option(e.namespace).flatMap(aliases.get).orNull, e.label, attrs, scope, minimizeEmpty = true, children: _*)
    case t: Atom[_] =>
      val s = t.text.trim
      if (s.isEmpty) Nil else Seq(Text(dates.getOrElse(s, s)))
    case other => Seq(other)
  }
}
