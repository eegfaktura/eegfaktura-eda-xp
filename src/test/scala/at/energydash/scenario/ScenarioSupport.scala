package at.energydash.scenario

import at.energydash.testsupport._
import io.circe.Json
import io.circe.parser.parse
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.marshalling.Marshal
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.stream.Materializer
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.xml.transform.{RewriteRule, RuleTransformer}
import scala.xml.{Elem, Node, Text}

/** One test broker, fake Ponton and MQTT recorder per scenario spec; a fresh graph per scenario unless kept. */
trait ScenarioSupport extends BeforeAndAfterEach with BeforeAndAfterAll { this: ScalaTestWithActorTestKit with Suite =>
  val Tenant = "RC100401"
  val Community = "AT00300000000RC100401000000000001"

  lazy val broker = TestBroker.start()
  lazy val ponton = new FakePonton()(system)
  lazy val mqtt: MqttRecorder = { broker; new MqttRecorder("eda/response/#") }

  // each step on its own: a recorder cut off by a broker restart must not keep the fake Ponton's port bound
  override def afterAll(): Unit = {
    scala.util.Try(mqtt.close()); scala.util.Try(ponton.stop())
    try super.afterAll() finally scala.util.Try(broker.stopServer())
  }

  /** Fresh database with the scenario tenants, fresh fake Ponton, nothing left on the recorder. */
  override def beforeEach(): Unit = {
    TestDb.reset()
    TestDb.seedTenant(Tenant, "KEP"); TestDb.seedTenant("RC100507", "KEP")
    TestDb.seedEeg(Tenant, Community)
    ponton.reset()
    while (mqtt.next(50.millis).isDefined) {}
  }

  def graph(name: String, journal: String = s"target/test-storage/scenario-${System.nanoTime()}"): ScenarioGraph = {
    broker; mqtt; ponton
    new ScenarioGraph(name, journal).start()
  }

  def request(json: Json): Unit = mqtt.publish("eda/request", json.noSpaces)

  /** The next message on `topic` (others are skipped), payload decoded (Base64+gzip for cr_msg). */
  def await(topic: String, timeout: FiniteDuration = 10.seconds): Json = {
    val deadline = timeout.fromNow
    Iterator.continually(mqtt.next(deadline.timeLeft max 1.milli)).takeWhile(_ => deadline.hasTimeLeft())
      .collectFirst { case Some(r) if r.topic == topic => decode(r) }
      .getOrElse(fail(s"nothing on $topic within $timeout"))
  }

  def decode(r: MqttRecorder#Received): Json = {
    val text = if (r.topic.endsWith("/cr_msg"))
      new String(new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder.decode(r.payload))).readAllBytes(), "UTF-8")
    else r.payload
    parse(text).fold(e => fail(s"not JSON on ${r.topic}: $e"), identity)
  }

  def awaitPonton(n: Int = 1, timeout: FiniteDuration = 10.seconds): List[FakePonton.Recorded] = {
    val deadline = timeout.fromNow
    while (ponton.requests.size < n && deadline.hasTimeLeft()) Thread.sleep(10)
    ponton.requests
  }

  def postMessage(g: ScenarioGraph, envelope: String): StatusCode = send(HttpRequest(HttpMethods.POST,
    s"${g.baseUrl}/pontonxp/message", entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, envelope)))

  def upload(g: ScenarioGraph, part: String, doc: String): StatusCode = {
    val form = Multipart.FormData(Multipart.FormData.BodyPart.Strict(part, HttpEntity(ContentTypes.`text/xml(UTF-8)`, doc),
      Map("filename" -> s"$part.xml")))
    send(HttpRequest(HttpMethods.POST, s"${g.baseUrl}/admin/upload",
      entity = Await.result(Marshal(form).to[RequestEntity](implicitly, system.executionContext), 5.seconds)))
  }

  private def send(req: HttpRequest): StatusCode = {
    val res = Await.result(Http()(system).singleRequest(req), 15.seconds)
    res.discardEntityBytes()(Materializer(system))
    res.status
  }

  /** An answer document of the grid operator for one of our requests: conversation id and receiver replaced. */
  def answerTo(fixture: String, conversationId: String, receiver: String = Tenant, code: Option[String] = None,
               dropElements: Set[String] = Set.empty): Elem = {
    val doc = Fixtures.ebUtilitiesDocument(Fixtures.xml(fixture)).get
    new RuleTransformer(new RewriteRule {
      override def transform(n: Node): Seq[Node] = n match {
        case e: Elem if dropElements(e.label) => Nil
        case e: Elem if e.label == "ConversationId" => e.copy(child = Text(conversationId))
        case e: Elem if e.label == "MessageCode" && code.isDefined => e.copy(child = Text(code.get))
        case e: Elem if e.label == "Receiver" => e.copy(child = e.child.map {
          case a: Elem if a.label == "MessageAddress" => a.copy(child = Text(receiver))
          case other => other
        })
        case other => other
      }
    }).transform(doc).head.asInstanceOf[Elem]
  }
}
