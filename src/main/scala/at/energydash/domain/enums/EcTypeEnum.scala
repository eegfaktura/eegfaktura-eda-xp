package at.energydash.domain.enums

import io.circe.{Decoder, Encoder}

object EcTypeEnum extends Enumeration {
  type EcTypeEnum = Value

  implicit val decoder: Decoder[Value] = Decoder.decodeEnumeration(this)
  implicit val encoder: Encoder[Value] = Encoder.encodeEnumeration(this)

  val BEG: EcTypeEnum.Value = Value("BEG")
  val LOCAL: EcTypeEnum.Value = Value("LOCAL")
  val REGIONAL: EcTypeEnum.Value = Value("REGIONAL")
  val GEA: EcTypeEnum.Value = Value("GEA")
  // Peer-to-Peer and Eigenversorgung (ElWG, platform#229): ECType PP / SC, only in ECMPList 01p20
  val P2P: EcTypeEnum.Value = Value("P2P")
  val EVA: EcTypeEnum.Value = Value("EVA")
}

object EcDisModelEnum extends Enumeration {
  type EcDisModelEnum = Value

  implicit val decoder: Decoder[Value] = Decoder.decodeEnumeration(this)
  implicit val encoder: Encoder[Value] = Encoder.encodeEnumeration(this)

  val STATIC: EcDisModelEnum.Value = Value("STATIC")
  val DYNAMIC: EcDisModelEnum.Value = Value("DYNAMIC")
}