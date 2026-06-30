package at.energydash.domain.xml

import at.energydash.domain.enums.{EbMsMessageType, MeterDirectionType}
import at.energydash.domain.{EbMsMessage, Meter}

// ECMPList Schema 01p20 (gültig ab 2026-10, "Erweiterung ECType"): ECType wird
// optional + neue Werte SC/PP; zusätzlich werden mehrere MPTimeData-Felder
// optional (EnergyDirection, ECPartFact, ECShare) bzw. neu (DataType, Purpose,
// TechCode, FuelCode, ECZoneLevel), und ECID wird optional. PlantCategory entfällt.
// Hier wird nur der EINGEHENDE Pfad abgedeckt (ZP-Listen-Antwort), damit eine in
// 01p20 gesendete Liste nicht im XmlParseHandler-Default (ERROR) verworfen wird.
// Das ausgehende Erzeugen (EC_PRTFACT_CHANGE) läuft weiterhin auf 01p10 — eine
// Outbound-Umstellung ist separat, falls/wenn wir 01p20 senden wollen.
class ECMPListV0120Document(doc: ecmplist.v01p20.ECMPList) {
  def toDoc: ecmplist.v01p20.ECMPList = doc

  def toMessage: EbMsMessage = {
    EbMsMessage(
      messageId = Some(doc.ProcessDirectory.MessageId),
      conversationId = doc.ProcessDirectory.ConversationId,
      sender = doc.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
      receiver = doc.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
      messageCode = EbMsMessageType.withName(doc.MarketParticipantDirectory.MessageCode.toString),
      messageCodeVersion = Some("02.00"),
      ecId = doc.ProcessDirectory.ECID,
      meterList = Some(doc.ProcessDirectory.MPListData
        .flatMap(m =>
          m.MPTimeData.map(mp =>
            Meter(
              meteringPoint = m.MeteringPoint,
              direction = mp.EnergyDirection.map(d => MeterDirectionType.withName(d.toString)),
              activation = Some(mp.DateActivate.toGregorianCalendar.getTime),
              partFact = mp.ECPartFact,
              from = Some(mp.DateFrom.toGregorianCalendar.getTime),
              to = Some(mp.DateTo.toGregorianCalendar.getTime),
              share = mp.ECShare,
              consentId = m.ConsentId
            ))
        )
      )
    )
  }
}

object ECMPListV0120Document {
  def apply(doc: ecmplist.v01p20.ECMPList) = new ECMPListV0120Document(doc)
}
