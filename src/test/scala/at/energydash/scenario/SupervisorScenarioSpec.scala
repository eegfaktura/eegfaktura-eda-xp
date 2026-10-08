package at.energydash.scenario

import at.energydash.actors.{Start, SupervisorActor}
import at.energydash.admin.{RegisterPontonRequest, RegisterPontonServiceClient}
import at.energydash.testsupport.{Fixtures, ProtocolCatalog}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.grpc.GrpcClientSettings
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.stream.Materializer
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

/**
 * S12: the real `SupervisorActor` on the test configuration (HTTP 16090, gRPC 19093, the companion's MQTT
 * request stream) — the wiring the scenario graph copies — fed with eegfaktura-v3's documents in a row.
 */
class SupervisorScenarioSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with ScenarioSupport with Eventually {
  private lazy val supervisor: ActorSystem[Any] = {
    broker; mqtt; ponton
    val journal = java.nio.file.Files.createDirectories(java.nio.file.Paths.get(s"target/test-storage/s12-${System.nanoTime()}/journal"))
    val config = ConfigFactory.parseString(s"""pekko.persistence.journal.leveldb.dir = "$journal"""")
      .withFallback(ActorTestKit.ApplicationTestConfig)
    val s = ActorSystem(SupervisorActor().asInstanceOf[org.apache.pekko.actor.typed.Behavior[Any]], "supervisor-s12", config)
    s ! Start
    s
  }
  override def afterAll(): Unit = { supervisor.terminate(); Await.ready(supervisor.whenTerminated, 20.seconds); super.afterAll() }

  private val base = "http://127.0.0.1:16090"
  private val process = ProtocolCatalog.codes.map(c => c.code -> c.process).toMap

  private def post(path: String, entity: RequestEntity): StatusCode = {
    val res = Await.result(Http()(system).singleRequest(HttpRequest(HttpMethods.POST, s"$base$path", entity = entity)), 15.seconds)
    res.discardEntityBytes()(Materializer(system))
    res.status
  }

  /** The supervisor binds asynchronously: retry the first request until the port answers. */
  private def awaitBound(): Unit = {
    supervisor
    eventually(timeout(10.seconds), interval(200.millis)) {
      post("/pontonxp/notification", HttpEntity(ContentTypes.`text/xml(UTF-8)`, "<n/>")) shouldBe StatusCodes.OK
    }
  }

  /** The supervisor's MQTT client connects asynchronously too: post the first document until it is published
   *  (what happens to a document posted before that is tested in M5, not here). */
  private def awaitMqtt(): Unit = {
    val doc = Fixtures.ebUtilitiesDocument(Fixtures.xml("v3/eda-xml/in/CPNotification-01.13-ANTWORT_PT-99.xml")).get
    val (sender, receiver) = Fixtures.routing(doc)
    val ok = Iterator.continually {
      post("/pontonxp/message", HttpEntity(ContentTypes.`text/xml(UTF-8)`, Fixtures.inboundEnvelope(doc, sender, receiver, "ANTWORT_PT")))
      Try(await(s"eda/response/${receiver.toLowerCase}/protocol/cr_req_pt", 500.millis)).isSuccess
    }.take(30).contains(true)
    assert(ok, "SupervisorActor's MQTT client did not connect within 15 s")
  }

  "S12: the real SupervisorActor" should {
    "publish every v3 document the Ponton envelope admits, and refuse the foreign one" in {
      awaitBound()
      awaitMqtt()
      ProtocolCatalog.inbound.filter(r => r.fixture.startsWith("v3/") && r.via == "ponton").foreach { row =>
        val doc = Fixtures.ebUtilitiesDocument(Fixtures.xml(row.fixture)).get
        val (sender, receiver) = Fixtures.routing(doc)
        post("/pontonxp/message", HttpEntity(ContentTypes.`text/xml(UTF-8)`, Fixtures.inboundEnvelope(doc, sender, receiver, row.code))) shouldBe StatusCodes.NoContent
        withClue(row.fileName)(await(s"eda/response/${receiver.toLowerCase}/protocol/${process(row.code).toLowerCase}"))
      }
      val foreign = Fixtures.xml("v3/eda-xml/in/EDASendError.xml")
      post("/pontonxp/message", HttpEntity(ContentTypes.`text/xml(UTF-8)`, Fixtures.inboundEnvelope(foreign, "AT003000", Tenant, "ERROR"))) shouldBe
        StatusCodes.InternalServerError
    }
    "accept the v3 consumption record 01.31 over /admin/upload" in {
      awaitBound()
      val doc = Fixtures.xml("v3/eda-xml/in/ConsumptionRecord-01.31-summer-96-no-ecid.xml")
      val form = Multipart.FormData(Multipart.FormData.BodyPart.Strict("CR_MSG_03.03", HttpEntity(ContentTypes.`text/xml(UTF-8)`, doc.toString), Map("filename" -> "cr.xml")))
      import org.apache.pekko.http.scaladsl.marshalling.Marshal
      post("/admin/upload", Await.result(Marshal(form).to[RequestEntity](implicitly, system.executionContext), 5.seconds)) shouldBe StatusCodes.Created
      val (_, receiver) = Fixtures.routing(doc)
      await(s"eda/response/${receiver.toLowerCase}/protocol/cr_msg")
    }
    "answer the gRPC register call on 127.0.0.1:19093" in {
      awaitBound()
      val client = RegisterPontonServiceClient(GrpcClientSettings.connectToServiceAt("127.0.0.1", 19093)(system).withTls(false))(system)
      try {
        // the provider initialises asynchronously; a register during the initialisation is dropped (261005-ca4)
        eventually(timeout(15.seconds), interval(300.millis)) {
          Await.result(client.register(RegisterPontonRequest("RC100403", "u", "p", "email.com", "KEP")), 10.seconds).status shouldBe 200
        }
      } finally Await.ready(client.close(), 10.seconds)
    }
  }
}
