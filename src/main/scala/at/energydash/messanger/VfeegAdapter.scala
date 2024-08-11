package at.energydash.messanger

import de.pontonconsulting.xmlpipe.adapter.{AdapterException, GenericAdapter, ISpecificAdapter, MessageResult}
import de.pontonconsulting.xmlpipe.message.{BackEndMessage, BackEndMessageException}

import java.io.{File, FileNotFoundException, IOException}
import scala.jdk.CollectionConverters._

class VfeegAdapter(id: String) extends ISpecificAdapter {
  val ga: GenericAdapter = new GenericAdapter(id)
  try {
    ga.setServerPort(0); // Use -1 if you don't want to receive any messages. Use 0 if any free port should be used. Default is 0.
    ga.setProcessingTimeout(1200); // Default is 600 (10 minutes).
    ga.setAdapterIP("127.0.0.1"); // This IP address is used by the messenger to send incoming messages to the adapter. If the IP address is not explicitly set, the first found IP will be used.
    ga.setEndAdapter(this);
    ga.addMessengerConnection("localhost", 8080, "/pontonxp/AdapterService") // Add more messenger connections, if you use cluster mode.
  } catch {
    case ae: AdapterException => {
      println(ae)
    }
    //  _log.fatal("Error while initializing SampleAdapter", ae);
    case ioe: IOException => {
      println(ioe)
      //  _log.fatal("Error while initializing SampleAdapter", ioe);
    }
  }

  override def getID: String = id

  override def getStatus: String = "VfeegAdapter is ready to receive Messages."

  override def supportsAcknowledgements(): Boolean = false

  override def supportsAttachments(): Boolean = true

  override def getNumberOfParallelThreads: Int = 1

  override def receiveMessage(message: BackEndMessage): MessageResult = {
    val attachments = message.getAttachments.asScala.toList
    attachments.foreach(a => println(a.getAbsolutePath))

    /* get the business document as input stream */
    message.getMessageDocumentInputStream
    /* save the business document as file */
    message.writeMessageDocumentTo(new File("payload.xml"))
    /* save the business document including the backend envelope as file */
    message.writeBackEndMessageTo(new File("full.xml"))
    /* save the backend envelope as file */
    message.writeBackEndEnvelopeTo(new File("envelope.xml"))

    new MessageResult(MessageResult.MSG_SUCCESSFULLY_RECEIVED)
  }

  override def receiveTestMessage(message: BackEndMessage): MessageResult = receiveMessage(message)

  override def receiveAcknowledgement(message: BackEndMessage): MessageResult = new MessageResult(MessageResult.MSG_SUCCESSFULLY_RECEIVED)

  override def getWorkFolder: File = new File("/home/petero/projects/energycash/Dev/Workfolder/")

  override def doSelfCheck(): Boolean = true

  override def shutdown(): String = {
    this.ga.shutdown()
    return null
  }

  def partnerExistenceTest() = {
    try {
      println("Partner 'papitest' exists: " + ga.partnerExists("papitest"))
      println("Partner 'papitest2' exists: " + ga.partnerExists("papitest2"))
      println("Partner 'papitest234' exists: " + ga.partnerExists("papitest234"))
    } catch {
      case e: AdapterException => {
        e.printStackTrace()
      }
    }
  }

  def getPartnerlistTest() = {
    try {
      val partners = ga.getFullPartnerList.toList
      partners.foreach(p => println("*** (all) Partner  has local id: " + p))

      val localPartners = ga.getLocalPartnerList.toList
      localPartners.foreach(p => println("*** (own) Partner has local id: " + p))

      val remotePartners = ga.getRemotePartnerList.toList
      remotePartners.foreach(p => println("*** (remote) Partner has local id: " + p))
    } catch  {
      case e: AdapterException => {
        e.printStackTrace();
      }
    }
  }

  def sendTest() = {
    try {
      // Create a new BackEndMessage from the XML file.
      val bem = new BackEndMessage(new File("/home/petero/projects/energycash/Dev/XPAdapter/20240411_ANFORDERUNG_ECON_sepp.gaug.xml"))
      // Set the sender and receiver organisation. These values have to match
      // internal IDs of partner profiles.
      // If the specified XML file already contains a BackEndMessage and the
      // sender and receiver information is set correctly,
      // these values don't have to be set manually.
      bem.setSenderOrganisation("sender")
      bem.setReceiverOrganisation("receiver")
      // optionally set specific Message Type.
      bem.setDTDSet("papinet2.1")
      bem.setMessageName("PurchaseOrder")
      bem.setDTDVersionNumber("V2R10")
      val result = ga.sendMessage(bem)
      if (!result.equals(new MessageResult(MessageResult.MSG_SUCCESSFULLY_SEND))) {
        println("transmission failed:" + result.toString())
      }
    } catch {
      case e: FileNotFoundException => {
        e.printStackTrace();
      }
      case e: AdapterException => {
        e.printStackTrace();
      }
      case bme: BackEndMessageException => {
        bme.printStackTrace()
      }
    }
  }
}

object VfeegAdapter {
  private val adapter = new VfeegAdapter("vfeeg-adapter")

  def apply(): VfeegAdapter = adapter
}


