package at.energydash.testsupport

import at.energydash.domain.EbMsMessage
import at.energydash.domain.XmlParseHandler
import at.energydash.domain.XmlParseHandler.ParseHeader
import at.energydash.domain.eda.MessageHelper
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.mqtt.path.MqttPaths
import at.energydash.testsupport.ProtocolCatalog.{Code, Inbound, Official, Outbound}
import org.apache.pekko.actor.typed.ActorSystem

import java.time.LocalDate
import scala.util.{Failure, Success, Try}
import scala.xml.XML

/**
 * The checks of the protocol tests as functions of their input (protocol-changes.md §4): every guard and the
 * outbound row check return the problems they find, empty when all is well. `ProtocolCatalogGuardSpec` and
 * `OutboundProtocolSpec` run them on the real catalog; `ProtocolSelfTestSpec` runs them on broken copies and
 * expects each to complain — so a guard that silently stops checking fails there.
 */
object ProtocolChecks {

  /** Everything the guards read besides the code: the catalog, the official list, the files. */
  final case class CatalogData(outbound: List[Outbound], inbound: List[Inbound], codes: List[Code],
                               official: List[Official], release: String, referenceDate: LocalDate,
                               unsupported: Map[String, String], headerProcessCode: Map[String, Set[String]],
                               notParsed: Set[String], typeLibraries: Set[String],
                               schemas: Map[String, String], goldenFiles: Set[String])

  lazy val real: CatalogData = CatalogData(
    ProtocolCatalog.outbound, ProtocolCatalog.inbound, ProtocolCatalog.codes, ProtocolCatalog.official,
    ProtocolCatalog.config.getString("official.release"), ProtocolCatalog.referenceDate, ProtocolCatalog.unsupported,
    ProtocolCatalog.headerProcessCode, ProtocolCatalog.notParsed, ProtocolCatalog.typeLibraries,
    Xsd.byNamespace.filter(_._1.startsWith("http://www.ebutilities.at/schemata/")), Golden.all)

  private def inboundNamespace(row: Inbound): Option[String] =
    Fixtures.ebUtilitiesDocument(Fixtures.xml(row.fixture)).map(_.namespace)

  private def inboundNamespaces(d: CatalogData): Set[String] = d.inbound.flatMap(inboundNamespace).toSet

  private def anyMessage(code: EbMsMessageType.Value) =
    EbMsMessage(messageId = Some("M1"), conversationId = "C1", sender = "RC100401", receiver = "AT003000",
      messageCode = code, requestId = Some("R1"))

  /** eda-xp has a builder for the code: it builds, throws, or refuses the missing label by name. */
  def hasBuilder(code: EbMsMessageType.Value): Boolean = {
    val (built, logs) = LogCapture("at.energydash.domain.eda")(Try(MessageHelper.getEdaMessageByType(anyMessage(code))))
    built match {
      case Success(Some(_)) | Failure(_) => true
      case Success(None) => LogCapture.messages(logs).exists(_.startsWith(s"Unknown version label <none> for $code"))
    }
  }

  // Guard 0
  def uniqueRows(d: CatalogData): List[String] = {
    val names = d.outbound.map(_.name)
    val fixtures = d.inbound.map(_.fixture)
    names.diff(names.distinct).map(n => s"duplicate outbound row $n") ++
      fixtures.diff(fixtures.distinct).map(f => s"duplicate inbound fixture $f")
  }

  // Guard 1
  def builders(d: CatalogData): List[String] = {
    val buildable = EbMsMessageType.values.toList.filter(hasBuilder).map(_.toString).toSet
    val withRows = d.outbound.map(_.code).toSet
    buildable.diff(withRows).toList.sorted.map(c => s"code with a builder but no catalog row: $c") ++
      withRows.diff(buildable).toList.sorted.map(c => s"catalog rows for a code without a builder: $c")
  }

  def refusals(d: CatalogData): List[String] =
    d.outbound.map(_.code).distinct.filterNot(_ == "ANFORDERUNG_GN").flatMap { code =>
      (if (d.outbound.exists(r => r.code == code && r.refused && r.label.isEmpty)) Nil
       else List(s"$code: no refused row without label")) ++
        (if (d.outbound.exists(r => r.code == code && r.refused && r.label.nonEmpty)) Nil
         else List(s"$code: no refused row with an unknown label"))
    }

  // Guard 3
  def schemas(d: CatalogData): List[String] = {
    val accounted = d.outbound.flatMap(_.xsd).map(x => d.schemas.find(_._2 == x).map(_._1).getOrElse(x)).toSet ++
      inboundNamespaces(d) ++ d.notParsed ++ d.typeLibraries
    d.schemas.filterNot { case (ns, _) => accounted(ns) }.values.toList.sorted
      .map(x => s"XSD without a catalog row, a not-parsed or a type-library entry: $x")
  }

  // Guard 4
  def parser(d: CatalogData): List[String] = {
    val inboundNs = inboundNamespaces(d)
    d.outbound.filter(r => r.sends && r.knownError.isEmpty && r.xsd.isDefined).flatMap { row =>
      MessageHelper.getEdaMessageByType(row.message) match {
        case None => List(s"${row.name}: does not build")
        case Some(built) =>
          val doc = XML.loadString(built.toXML.toString)
          val parsed = XmlParseHandler.mapXmlToEbms(ParseHeader("S", "R", Some("C"), Some(row.code)), doc)
          d.schemas.find(_._2 == row.xsd.get).map(_._1) match {
            case None => List(s"${row.name}: xsd ${row.xsd.get} is not an ebUtilities schema")
            case Some(ns) if inboundNs(ns) =>
              if (parsed.messageCode.toString == row.code) Nil
              else List(s"${row.name}: parsed as ${parsed.messageCode}, not ${row.code}")
            case Some(_) =>
              if (parsed.errorMessage.getOrElse("").startsWith("Unknown or not registered MessageType")) Nil
              else List(s"${row.name}: parsed although its namespace has no inbound row")
          }
      }
    }
  }

  // Guard 5
  def golden(d: CatalogData): List[String] = {
    val expected = d.outbound.filter(r => r.sends && r.knownError.isEmpty)
      .flatMap(r => Seq(r.goldenBase + ".xml", r.goldenBase + ".header")).toSet ++ d.inbound.map(_.golden)
    (d.goldenFiles -- expected).toList.sorted.map(f => s"orphan golden file $f")
  }

  // Guard 6
  def codes(d: CatalogData): List[String] = {
    val paths = new MqttPaths {}
    val values = EbMsMessageType.values.toList.map(_.toString).filterNot(_ == "ERROR_MESSAGE")
    values.diff(d.codes.map(_.code)).map(c => s"code without a `codes` row: $c") ++ d.codes.flatMap { c =>
      Try(EbMsMessageType.withName(c.code)).toOption match {
        case None => List(s"${c.code}: not an EbMsMessageType")
        case Some(code) =>
          val process = Try(MessageHelper.EDAMessageCodeToProcessCode(code).toString).getOrElse("<throws>")
          val topic = paths.edaReqResPath("RC100401", c.process)
          (if (process == c.process) Nil else List(s"${c.code}: process is $process, not ${c.process}")) ++
            (if (topic == s"eda/response/rc100401/protocol/${c.process.toLowerCase}") Nil else List(s"${c.code}: topic $topic")) ++
            c.headerType.toList.flatMap { t =>
              val actual = MessageHelper.pontonHeaderMessageType(code)
              if (actual == t) Nil else List(s"${c.code}: Ponton header type is $actual, not $t")
            }
      }
    }
  }

  // Guard 7
  def pontonPath(d: CatalogData): List[String] = {
    val choice = XML.loadFile(new java.io.File(Xsd.Dir, "InboundDocument.xsd"))
    val admitted = (choice \\ "element").flatMap(_.attribute("ref")).map(_.text).flatMap { ref =>
      ref.split(':') match { case Array(p, local) => Option(choice.scope.getURI(p)).map(_ -> local); case _ => None }
    }.toSet
    d.inbound.flatMap { row =>
      Fixtures.ebUtilitiesDocument(Fixtures.xml(row.fixture)).toList.flatMap { doc =>
        val key = doc.namespace -> doc.label
        row.via match {
          case "ponton" if !admitted(key) => List(s"${row.name}: not in InboundDocument.xsd's message choice")
          case "upload" if admitted(key) => List(s"${row.name}: is in the message choice, so via must be ponton")
          case "ponton" | "upload" => Nil
          case other => List(s"${row.name}: via must be ponton or upload for an ebUtilities document, not $other")
        }
      }
    }
  }

  // Guard 8
  private def current(d: CatalogData) = d.official.filter(_.validOn(d.referenceDate))

  def officialRequests(d: CatalogData): List[String] =
    current(d).filter(o => o.sentByEdaXp && !d.unsupported.contains(o.code)).filterNot { o =>
      d.outbound.exists(r => r.code == o.code && r.sends && r.knownError.isEmpty &&
        r.headerVersion.contains(o.schemaSetVersion) && r.xsd.contains(o.schema + ".xsd"))
    }.map(o => s"official request (${d.release}) without a matching row: ${o.code} ${o.schemaSet} ${o.schema}")

  def officialAnswers(d: CatalogData): List[String] = {
    val inboundNs = inboundNamespaces(d)
    val received = current(d).filter(o => o.receivedByEdaXp && !d.unsupported.contains(o.code))
    received.filterNot(o => inboundNs(o.namespace)).map(o => s"official answer whose schema has no inbound row: ${o.code} ${o.schema}") ++
      received.filterNot(o => Try(EbMsMessageType.withName(o.code)).isSuccess).map(o => s"official code unknown to EbMsMessageType: ${o.code}")
  }

  def unsupportedListed(d: CatalogData): List[String] =
    d.unsupported.keySet.diff(d.official.map(_.code).toSet).toList.sorted.map(c => s"unsupported code not in the official list: $c")

  // Guard 9
  def headerRule(d: CatalogData): List[String] = d.outbound.filter(_.sends).flatMap { r =>
    d.codes.find(_.code == r.code) match {
      case None => List(s"${r.name}: no `codes` row")
      case Some(c) =>
        val expectedType = d.headerProcessCode.get(r.code) match {
          case Some(labels) if labels(r.label.getOrElse("none")) => c.process
          case _ => r.code
        }
        (if (r.headerType.contains(expectedType)) Nil else List(s"${r.name}: MessageType ${r.headerType.getOrElse("<none>")}, rule says $expectedType")) ++
          r.label.toList.flatMap(l => if (r.headerVersion.contains(l)) Nil else List(s"${r.name}: MessageVersion ${r.headerVersion.getOrElse("<none>")}, rule says $l"))
    }
  }

  def headerCodes(d: CatalogData): List[String] =
    d.headerProcessCode.keySet.diff(d.outbound.map(_.code).toSet).toList.sorted.map(c => s"header-process-code for a code without requests: $c") ++
      d.codes.filter(_.headerType.isDefined).flatMap { c =>
        val expected = if (d.headerProcessCode.contains(c.code)) c.process else c.code
        if (c.headerType.contains(expected)) Nil else List(s"${c.code}: codes header-type ${c.headerType.get}, rule says $expected")
      }

  /** All guards by name, for the guard spec. */
  val guards: List[(String, CatalogData => List[String])] = List(
    "Guard 0 — unique rows" -> uniqueRows, "Guard 1 — builders" -> builders, "Guard 1 — refusals" -> refusals,
    "Guard 3 — schemas" -> schemas, "Guard 4 — parser" -> parser, "Guard 5 — golden files" -> golden,
    "Guard 6 — message codes" -> codes, "Guard 7 — Ponton path" -> pontonPath,
    "Guard 8 — official requests" -> officialRequests, "Guard 8 — official answers" -> officialAnswers,
    "Guard 8 — unsupported codes" -> unsupportedListed, "Guard 9 — header rule" -> headerRule,
    "Guard 9 — header codes" -> headerCodes)

  // Outbound rows

  /** What eda-xp did with a row's request: the builder chosen, the build log, the SOAP request or the failure. */
  final case class OutboundRun(builder: Option[String], logs: List[String], sent: Try[SoapCapture.Captured])

  def run(row: Outbound)(implicit system: ActorSystem[_]): OutboundRun = {
    val (built, logs) = LogCapture("at.energydash.domain.eda")(MessageHelper.getEdaMessageByType(row.message))
    OutboundRun(built.map(_.getClass.getSimpleName), LogCapture.messages(logs), Try(SoapCapture.send(row.message)))
  }

  /** The row's expectation against what eda-xp did; golden files are compared separately. */
  def outbound(row: Outbound, run: OutboundRun): List[String] = {
    val refusedLog = run.logs.exists(_.startsWith(s"Unknown version label ${row.label.getOrElse("<none>")} for ${row.code}"))
    if (row.refused)
      (if (run.builder.isEmpty) Nil else List(s"${row.name}: refused label built ${run.builder.get}")) ++
        (if (refusedLog) Nil else List(s"${row.name}: no ERROR naming the refused label")) ++
        (if (run.sent.isFailure) Nil else List(s"${row.name}: refused label was sent"))
    else if (row.expectFailure)
      if (run.sent.isFailure) Nil else List(s"${row.name}: must fail, but was sent")
    else run.sent match {
      case Failure(e) => List(s"${row.name}: not sent (${e.getMessage})")
      case Success(sent) =>
        val header = sent.pontonHeader
        (if (refusedLog) List(s"${row.name}: label refused") else Nil) ++
          (if (run.builder.isEmpty) List(s"${row.name}: no builder") else Nil) ++
          row.builder.toList.flatMap(b => if (run.builder.contains(b)) Nil else List(s"${row.name}: builder ${run.builder.getOrElse("<none>")}, not $b")) ++
          row.headerType.toList.flatMap(t => if (header.get("MessageType").contains(t)) Nil else List(s"${row.name}: header MessageType ${header.getOrElse("MessageType", "<none>")}, not $t")) ++
          row.headerVersion.toList.flatMap(v => if (header.get("MessageVersion").contains(v)) Nil else List(s"${row.name}: header MessageVersion ${header.getOrElse("MessageVersion", "<none>")}, not $v")) ++
          row.xsd.toList.flatMap(x => Try(Xsd.validate(sent.document, x)).failed.toOption.map(e => s"${row.name}: invalid against $x: ${e.getMessage.take(200)}").toList)
    }
  }
}
