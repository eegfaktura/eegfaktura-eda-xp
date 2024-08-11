package at.energydash.mqtt.path

trait MqttPaths {

  private def normalizeProtocolId(id: String): String = {
    id.toLowerCase match {
      case "cr_msg_03.03" => "cr_msg"
      case id_l => id_l
    }
  }

  private def edaPath(tenant: String) = if (tenant == "") "error" else tenant.toLowerCase()

  protected def edaProtocolModulePath(tenant: String, protocolId: String) =
    s"${edaPath(tenant)}/protocol/${normalizeProtocolId(protocolId)}"

  protected def edaCommandModulePath(tenant: String, protocolId: String) =
    s"${edaPath(tenant)}/command/${normalizeProtocolId(protocolId)}"

  def edaReqResPath(tenant: String, protocolId: String) =
    s"eda/response/${edaProtocolModulePath(tenant, protocolId)}"
}
