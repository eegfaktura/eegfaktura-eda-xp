package at.energydash.interfaces

import at.energydash.EmbeddedDb
import at.energydash.actors.MqttPublisher.{MqttCommand, MqttPublishCommand}
import at.energydash.actors.TenantProvider.TenantStart
import at.energydash.actors.{AdminServer, EdaCommand, PassEdaCommand, SendEdaResponse, TenantProvider}
import at.energydash.admin.{RegisterPontonRequest, RegisterPontonServiceClient, RegisteredPontonReply}
import at.energydash.service.AdminServiceImpl
import at.energydash.testsupport.KnownError.knownError
import at.energydash.testsupport.{FakePonton, ProtocolCatalog, TestDb, Tenants}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.grpc.GrpcClientSettings
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Await
import scala.concurrent.duration._

/** gRPC `RegisterPontonService` (caller: admin-backend) against a real TenantProvider on the test database. */
class AdminGrpcSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with EmbeddedDb with BeforeAndAfterEach {
  private lazy val ponton = new FakePonton()(system)
  override def beforeEach(): Unit = { TestDb.reset(); ponton.reset() }
  override def afterAll(): Unit = { ponton.stop(); super.afterAll() }

  private def provider() = {
    val publisher = createTestProbe[MqttCommand]()
    val p = spawn(TenantProvider(publisher.ref))
    p ! TenantStart
    Tenants.awaitReady(p, testKit)
    (p, publisher)
  }
  private def request(tenant: String, cType: String) = RegisterPontonRequest(tenant = tenant, username = "u",
    password = "secret", domain = "email.com", pontonCommType = cType)
  private def await[T](f: scala.concurrent.Future[T]): T = Await.result(f, 15.seconds)

  "AdminServiceImpl" should {
    "register a KEP tenant: 200, row stored, online state published" in {
      val (p, publisher) = provider()
      val service = new AdminServiceImpl(p)(system.scheduler, system.executionContext)
      await(service.register(request("RC100401", "KEP"))) shouldBe RegisteredPontonReply(200, "OK")
      TestDb.count("SELECT count(*) FROM eda.tenantconfig WHERE tenant = 'RC100401' AND type = 'KEP' AND active") shouldBe 1
      val online = publisher.expectMessageType[MqttPublishCommand]
      (online.command.tenant, online.command.command) shouldBe (("RC100401", "pontonOnlineState"))
    }
    "accept an update of the same tenant twice" in {
      val (p, _) = provider()
      val service = new AdminServiceImpl(p)(system.scheduler, system.executionContext)
      await(service.register(request("RC100401", "KEP"))).status shouldBe 200
      await(service.update(request("RC100401", "KEP"))).status shouldBe 200
      await(service.update(request("RC100401", "KEP"))).status shouldBe 200
      TestDb.count("SELECT count(*) FROM eda.tenantconfig WHERE tenant = 'RC100401'") shouldBe 1
    }
    // #6: admin-backend registers with an empty type; the tenant then goes to a MAIL worker instead of Ponton.
    "keep a tenant registered without type on the Ponton path [#6]" in knownError("6") {
      val (p, _) = provider()
      val service = new AdminServiceImpl(p)(system.scheduler, system.executionContext)
      await(service.register(request("RC100401", ""))).status shouldBe 200
      val reply = createTestProbe[EdaCommand]()
      val msg = ProtocolCatalog.outbound.find(r => r.code == "ANFORDERUNG_ECP" && r.label.contains("02.10")).get.message
      p ! PassEdaCommand("RC100401", msg, reply.ref)
      reply.expectMessageType[SendEdaResponse](3.seconds)
      ponton.requests should have size 1
    }
  }

  "AdminServer (bound gRPC server)" should {
    "answer the generated client on loopback" in {
      val (p, _) = provider()
      val binding = await(AdminServer(p, system))
      val client = RegisterPontonServiceClient(GrpcClientSettings.connectToServiceAt("127.0.0.1", binding.localAddress.getPort)(system).withTls(false))(system)
      try await(client.register(request("RC100402", "KEP"))) shouldBe RegisteredPontonReply(200, "OK")
      finally { await(client.close()); await(binding.unbind()) }
    }
  }
}
