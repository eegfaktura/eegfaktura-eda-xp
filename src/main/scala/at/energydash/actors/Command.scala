package at.energydash.actors

sealed trait Command

case object Start extends Command
