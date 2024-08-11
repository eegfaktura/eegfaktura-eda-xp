package at.energydash.actors

sealed trait Command
sealed trait Response

case class Message[T](value: T) extends Command

case object Shutdown extends Command
case object Start extends Command
