package at.energydash.domain.eda

import at.energydash.domain.EbMsMessage
import org.scalatest.{EitherValues, TryValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import io.circe.generic.auto._
import io.circe.parser.decode

import java.io.{File, StringReader}
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import java.util.{Calendar, GregorianCalendar, Locale}

class ECPartitionChangeSpec extends AnyWordSpec with Matchers with EitherValues with TryValues {
  import at.energydash.domain.JsonImplicit._

  "Revoke Message" should {
    "Parse to XML" in {
      val jsonObjectStr =
        """{
          |"conversationId":"RC100713202409241727196030000019844",
          |"messageId":"RC100713202409241727196030000019843",
          |"sender":"RC100713",
          |"receiver":"AT002000",
          |"messageCode":"ANFORDERUNG_CPF",
          |"messageCodeVersion":"01.00",
          |"requestId":"AgcZPFj",
          |"ecId":"AT00200000000RC100713000000000282",
          |"ecType":"REGIONAL",
          |"ecDisModel":"DYNAMIC",
          |"meterList":[
          | {
          |  "meteringPoint":"AT0020000000000000000000021091270",
          |  "direction":"GENERATION",
          |  "activation":1719532800000,
          |  "partFact":25
          | }
          |]}""".stripMargin

      val message = decode[EbMsMessage](jsonObjectStr)

      val node = ECPartitionChangeMessage(message.value).getVersion().success.value.toXML

      val expectedProcessDate = new GregorianCalendar(new Locale("de", "AT"))
      expectedProcessDate.add(Calendar.DAY_OF_MONTH, 1)

      (node \ "ProcessDirectory" \ "MPListData" \ "MPTimeData" \ "ECPartFact").text shouldBe "25"
      (node \ "ProcessDirectory" \ "MPListData" \ "MPTimeData" \ "DateFrom").text shouldBe MessageHelper.buildCalendarDate(expectedProcessDate.getTime)
      (node \ "ProcessDirectory" \ "ProcessDate" ).text shouldBe MessageHelper.buildCalendarDate(expectedProcessDate.getTime)
    }
  }


  "Partition change 01.10 (EC_PRTFACT_CHANGE_01.10)" should {
    val json =
      """{
        |"conversationId":"RC100713202610061727196030000019844",
        |"messageId":"RC100713202610061727196030000019843",
        |"sender":"RC100713",
        |"receiver":"AT002000",
        |"messageCode":"ANFORDERUNG_CPF",
        |"messageCodeVersion":"01.10",
        |"requestId":"AgcZPFj",
        |"ecId":"AT00200000000RC100713000000000282",
        |"ecType":"REGIONAL",
        |"ecDisModel":"DYNAMIC",
        |"meterList":[
        | {
        |  "meteringPoint":"AT0020000000000000000000021091270",
        |  "direction":"GENERATION",
        |  "activation":1719532800000,
        |  "partFact":25
        | }
        |]}""".stripMargin

    "build ECMPList 01p20 that is valid against the official XSD" in {
      val m = decode[EbMsMessage](json).toOption.get
      val built = ECPartitionChangeMessage(m).getVersion().get
      built shouldBe a[ECPartitionChangeXMLMessageV0110]
      val node = built.toXML

      node.namespace shouldBe "http://www.ebutilities.at/schemata/customerprocesses/ecmplist/01p20"
      (node \ "MarketParticipantDirectory" \ "@SchemaVersion").text shouldBe "01.20"
      (node \ "MarketParticipantDirectory" \ "MessageCode").text shouldBe "ANFORDERUNG_CPF"
      val td = node \ "ProcessDirectory" \ "MPListData" \ "MPTimeData"
      (td \ "ECPartFact").text shouldBe "25"
      (td \ "DataType").text shouldBe "EnergyCommunityRegistration"
      (td \ "EnergyDirection").text shouldBe "GENERATION"

      val schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
        .newSchema(new File("src/main/xsd/ECMPList_01p20.xsd"))
      noException should be thrownBy schema.newValidator().validate(new StreamSource(new StringReader(node.toString)))
    }

    "refuse a request without ecId or participation factor" in {
      val m = decode[EbMsMessage](json).toOption.get
      an[IllegalArgumentException] should be thrownBy ECPartitionChangeMessage(m.copy(ecId = None)).getVersion().get.toXML
      val noFactor = m.copy(meterList = m.meterList.map(_.map(_.copy(partFact = None))))
      an[IllegalArgumentException] should be thrownBy ECPartitionChangeMessage(noFactor).getVersion().get.toXML
    }

    "keep 01p10 for version 01.00" in {
      val m = decode[EbMsMessage](json.replace("\"01.10\"", "\"01.00\"")).toOption.get
      ECPartitionChangeMessage(m).getVersion().get shouldBe a[ECPartitionChangeXMLMessage]
    }
  }
}
