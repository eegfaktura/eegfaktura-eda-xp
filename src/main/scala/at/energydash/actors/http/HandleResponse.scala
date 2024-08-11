package at.energydash.actors.http


object HandleResponse {

//  private[this] val SOAP_ENVELOPE_URI =
//    "http://www.w3.org/2003/05/soap-envelope"
//
//  def responseBodyXml(envelope: Envelope)(implicit ec: ExecutionContext): Future[NodeSeq] = {
////    val envelope = scalaxb.fromXML[Envelope](responseXml)
//
//    Future {
//      envelope.Body.any.headOption match {
//        case Some(DataRecord(_, _, x: scala.xml.Elem))
//          if (x.label == "Fault") &&
//            (x.scope.getURI(x.prefix) == SOAP_ENVELOPE_URI) =>
//          // TODO: much advance failure handling
//          // val fault = scalaxb.fromXML[soapenvelope12.Fault](x)
//          Nil
//        case _ =>
//          envelope.Body.any.collect {
//            case DataRecord(_, _, x: scala.xml.Node) => x
//          }
//      }
//    }
//  }
//
//  def responseInbound(envelope: Envelope)(implicit ec: ExecutionContext) : Future[InboundDocument] = {
//    responseBodyXml(envelope).map(n => scalaxb.fromXML[InboundDocument](n.head))
//  }
//
//  def reponseEbMsMessage(envelope: Envelope)(implicit ec: ExecutionContext) : Future[EbMsMessage] = {
//    responseInbound(envelope).map(x => x.Message.messageoption match {
//      case DataRecord(_, _, x: v01p10.ECMPList) => ECMPListV0110Document(x).toMessage
////      case DataRecord(_, _, x: v01p00.ECMPList) => ECMPListV0110Document(x).toMessage
//      case DataRecord(_, _, x: cmnotification.v01p11.CMNotification) => CMNotificationV0111Document(x).toMessage
//      case DataRecord(_, _, x: cmrevoke.v01p00.CMRevoke) => CMRevokeV0100Document(x).toMessage
//      case DataRecord(_, _, x: consumptionrecord.v01p30.ConsumptionRecord) => ConsumptionRecordV0130(x).toMessage
//      case DataRecord(_, _, x: consumptionrecord.v01p40.ConsumptionRecord) => ConsumptionRecordV0140(x).toMessage
//      case DataRecord(_, _, x: cpnotification.v01p13.CPNotification) => CPNotificationV0113Document(x).toMessage
//      case _ => EbMsMessage(
//        conversationId = x.Header.ConversationId,
//        sender = x.Header.SenderId,
//        receiver = x.Header.ReceiverId,
//        messageCode = EbMsMessageType.ERROR_MESSAGE,
//        errorMessage = Some(s"Unknown or not registered MessageType ${x.Header.MessageType}")
//      )
//    })
//  }
}
