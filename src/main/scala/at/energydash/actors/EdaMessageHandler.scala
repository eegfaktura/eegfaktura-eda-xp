package at.energydash.actors

//import akka.actor.typed.scaladsl.Behaviors
//import akka.actor.typed.{ActorRef, Behavior}
//
//class EdaMessageHandler(messageStore: ActorRef[MessageStorage.Command[_]]) {
//
//  def start: Behavior[EdaCommand] = Behaviors.setup[EdaCommand] { context => {
//    def process(): Behavior[EdaCommand] = {
//      Behaviors.receiveMessage {
//        case ReceiveEdaRequest(tenant, message) =>
//          Behaviors.same
//      }
//
//    }
//    process()
//  }}
//}
