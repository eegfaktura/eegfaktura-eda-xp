package at.energydash.testsupport

import at.energydash.domain.EbMsMessage
import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import io.circe.generic.auto._
import io.circe.parser.{decode, parse}
import io.circe.Json

import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.Using

/**
 * The protocol catalog `src/test/resources/protocol/catalog.conf` (protocol-changes.md §2): one row per
 * outbound (message code, version label) and per inbound fixture. Specs register one test per row, so a
 * protocol change is tested by adding a row, not by writing a test.
 */
object ProtocolCatalog {
  import at.energydash.domain.JsonImplicit._

  final case class Outbound(code: String, label: Option[String], builder: Option[String], xsd: Option[String],
                            headerType: Option[String], headerVersion: Option[String], refused: Boolean,
                            knownError: Option[String], expectFailure: Boolean, patch: Option[Json],
                            variant: Option[String] = None) {
    def name: String = s"$code ${label.getOrElse("<no label>")}" + variant.fold("")(v => s" ($v)") + knownError.fold("")(id => s" [$id]")
    def goldenBase: String = s"out/${code}_${label.getOrElse("none")}"
    /** The request is built and sent: the row has golden files (a known error states its outcome instead). */
    def sends: Boolean = !refused && !expectFailure

    /** The sample request of the code with this row's label and patch applied. */
    def message: EbMsMessage = {
      val base = parse(resource(s"protocol/samples/$code.json")).fold(throw _, identity)
      val withLabel = base.deepMerge(Json.obj("messageCodeVersion" -> label.fold(Json.Null)(Json.fromString)))
      val merged = patch.fold(withLabel)(withLabel.deepMerge)
      decode[EbMsMessage](merged.deepDropNullValues.noSpaces).fold(throw _, identity)
    }
  }

  final case class Inbound(fixture: String, code: String, via: String) {
    def fileName: String = fixture.split('/').last
    def name: String = s"$fileName → $code ($via)"
    def golden: String = s"in/${fileName.stripSuffix(".xml")}.json"
  }

  lazy val inbound: List[Inbound] =
    config.getConfigList("inbound").asScala.toList.map(c => Inbound(c.getString("fixture"), c.getString("code"), c.getString("via")))

  final case class Code(code: String, process: String, headerType: Option[String])

  lazy val codes: List[Code] =
    config.getConfigList("codes").asScala.toList.map(c => Code(c.getString("code"), c.getString("process"), opt(c, "header-type")))

  lazy val notParsed: Set[String] = config.getConfigList("not-parsed").asScala.map(_.getString("ns")).toSet
  lazy val typeLibraries: Set[String] = config.getStringList("type-libraries").asScala.toSet
  lazy val referenceDate: java.time.LocalDate = java.time.LocalDate.parse(config.getString("official.reference-date"))
  lazy val unsupported: Map[String, String] =
    config.getConfigList("official.unsupported").asScala.map(c => c.getString("code") -> c.getString("reason")).toMap

  val EdaXpRoles: Set[String] = Set("AB", "LA", "SP")

  /** One row of marktprozesse.csv (the official interface list). */
  final case class Official(process: String, processVersion: String, code: String, sender: String, receiver: String,
                            validFrom: java.time.LocalDate, validTo: Option[java.time.LocalDate], schema: String,
                            namespace: String, schemaSet: String) {
    def schemaSetVersion: String = schemaSet.split('_').last
    def validOn(d: java.time.LocalDate): Boolean = !validFrom.isAfter(d) && validTo.forall(!_.isBefore(d))
    /** eda-xp acts as the community (AB), the data receiver (LA) or the service provider (SP). */
    def sentByEdaXp: Boolean = sender.split('/').exists(EdaXpRoles)
    def receivedByEdaXp: Boolean = receiver.split('/').exists(EdaXpRoles)
  }

  lazy val official: List[Official] = resource("protocol/marktprozesse.csv").linesIterator
    .filterNot(l => l.startsWith("#") || l.startsWith("process,") || l.trim.isEmpty).toList.map { l =>
      val f = l.split(",", -1)
      Official(f(0), f(1), f(2), f(3), f(4), java.time.LocalDate.parse(f(5)),
        Option(f(6)).filter(_.nonEmpty).map(java.time.LocalDate.parse), f(7), f(9), f(10))
    }

  private def resource(path: String): String = Using.resource(Source.fromResource(path))(_.mkString)

  lazy val config: Config = ConfigFactory.parseResources("protocol/catalog.conf").resolve()

  /** Codes whose Ponton header still carries the process code, with the labels this is accepted for. */
  lazy val headerProcessCode: Map[String, Set[String]] =
    config.getConfigList("header-process-code").asScala.map(c => c.getString("code") -> c.getStringList("labels").asScala.toSet).toMap

  private def opt(c: Config, path: String): Option[String] = if (c.hasPath(path)) Some(c.getString(path)) else None

  private def json(c: Config, path: String): Option[Json] =
    if (!c.hasPath(path)) None
    else parse(c.getValue(path).render(ConfigRenderOptions.concise())).toOption

  lazy val outbound: List[Outbound] =
    config.getConfigList("outbound").asScala.toList.map { c =>
      Outbound(c.getString("code"), opt(c, "label"), opt(c, "builder"), opt(c, "xsd"), opt(c, "header.type"),
        opt(c, "header.version"), c.hasPath("refused") && c.getBoolean("refused"), opt(c, "known-error"),
        c.hasPath("expect.failure") && c.getBoolean("expect.failure"), json(c, "patch"), opt(c, "variant"))
    }
}
