package at.energydash.actors

sealed trait Command

case object Shutdown extends Command
case object Start extends Command
