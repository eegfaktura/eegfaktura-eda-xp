package at.energydash.domain.dao

import io.circe.Json

case class Conversation(id: String, conversation: Option[Json]) {

}
