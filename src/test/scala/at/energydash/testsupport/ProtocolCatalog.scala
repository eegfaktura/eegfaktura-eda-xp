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
                            headerType: Option[String], headerVersion: Option[String], knownError: Option[String],
                            expectFailure: Boolean, patch: Option[Json]) {
    def name: String = s"$code ${label.getOrElse("<no label>")}" + knownError.fold("")(id => s" [$id]")
    def goldenBase: String = s"out/${code}_${label.getOrElse("none")}"

    /** The sample request of the code with this row's label and patch applied. */
    def message: EbMsMessage = {
      val base = parse(resource(s"protocol/samples/$code.json")).fold(throw _, identity)
      val withLabel = base.deepMerge(Json.obj("messageCodeVersion" -> label.fold(Json.Null)(Json.fromString)))
      val merged = patch.fold(withLabel)(withLabel.deepMerge)
      decode[EbMsMessage](merged.deepDropNullValues.noSpaces).fold(throw _, identity)
    }
  }

  private def resource(path: String): String = Using.resource(Source.fromResource(path))(_.mkString)

  lazy val config: Config = ConfigFactory.parseResources("protocol/catalog.conf").resolve()

  private def opt(c: Config, path: String): Option[String] = if (c.hasPath(path)) Some(c.getString(path)) else None

  private def json(c: Config, path: String): Option[Json] =
    if (!c.hasPath(path)) None
    else parse(c.getValue(path).render(ConfigRenderOptions.concise())).toOption

  lazy val outbound: List[Outbound] =
    config.getConfigList("outbound").asScala.toList.map { c =>
      Outbound(c.getString("code"), opt(c, "label"), opt(c, "builder"), opt(c, "xsd"), opt(c, "header.type"),
        opt(c, "header.version"), opt(c, "known-error"),
        c.hasPath("expect.failure") && c.getBoolean("expect.failure"), json(c, "patch"))
    }
}
