package at.energydash.logging

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/**
 * The never-log rule, enforced on the source (dev#45 L11, logging concept W-261008-ca2): reads every log call under
 * `src/main/scala` and fails on a value that is not known to be safe, on `getMessage` without the exception and on
 * personal names. The scan is a positive list: a new `$value` fails until it is added to [[NeverLogScan.Allowed]]
 * with the reason why it holds no personal data and no document. That list is the only way past the scan.
 */
class NeverLogSpec extends AnyWordSpec with Matchers {
  import NeverLogScan._

  private lazy val sources: Seq[(String, String)] = {
    val root = Paths.get("src/main/scala")
    Files.walk(root).iterator().asScala.filter(_.toString.endsWith(".scala")).toList
      .map((p: Path) => root.relativize(p).toString.replace('\\', '/') -> Files.readString(p))
  }
  private lazy val calls: Seq[Call] = sources.flatMap { case (file, text) => logCalls(file, text) }

  private def findingsOf(code: String): Seq[String] = logCalls("T.scala", code).flatMap(findings)

  "The log calls of the service" should {
    "be found: the scan reads real code" in {
      sources.size should be > 50
      calls.size should be > 100
    }
    "hold nothing that may not be logged" in {
      val all = calls.flatMap(findings)
      withClue(all.mkString("\n", "\n", "\n"))(all shouldBe empty)
    }
    "have a reason for every allowlist entry and no entry without a use" in {
      Allowed.filter(_._2.trim.length < 10).keys shouldBe empty
      val used = calls.flatMap(_.values).map(_._1).flatMap(allowedEntry).toSet
      (Allowed.keySet -- used) shouldBe empty
    }
  }

  "The scan" should {
    "refuse a value that is not on the positive list" in {
      findingsOf("""logger.info(s"got $message")""") should have size 1
      findingsOf("""log.warn(s"got ${message}")""") should have size 1
      findingsOf("""context.log.info(s"got ${req.member}")""") should have size 1
      findingsOf("""logger.error(text2)""") should have size 1
      findingsOf("""logger.info("x {}", member)""") should have size 1
      findingsOf("""ctx.setMessage(s"$member")""") should have size 1
    }
    "accept a value on the positive list and plain text" in {
      findingsOf("""logger.info(s"conversation ${message.conversationId}")""") shouldBe empty
      findingsOf("""logger.info("Send Email (no value here)")""") shouldBe empty
    }
    "refuse toString and an exception in the text" in {
      findingsOf("""logger.error(e.toString)""").mkString should include("toString")
      findingsOf("""logger.info(s"${message.conversationId.toString}")""").mkString should include("toString")
      findingsOf("""logger.error(s"failed: $e")""") should have size 1
      findingsOf("""logger.error(s"failed: $e", e)""") should have size 1
    }
    "refuse getMessage without the exception, and accept it with" in {
      findingsOf("""logger.error(e.getMessage)""").mkString should include("getMessage")
      findingsOf("""logger.warn(s"failed: ${e.getMessage}")""").mkString should include("getMessage")
      findingsOf("""logger.warn(s"failed: ${e.getMessage()}")""").mkString should include("getMessage")
      findingsOf("""logger.error(s"failed: ${e.getMessage}", other)""").mkString should include("getMessage")
      findingsOf("""logger.error(s"failed: ${e.getMessage}", e)""") shouldBe empty
      findingsOf("""logger.error(e.getMessage, e)""") shouldBe empty
    }
    "refuse personal names in a value and as a label in the text" in {
      Seq("firstname", "lastName", "email", "toEmail", "iban", "password", "token", "soapBody").foreach { name =>
        withClue(name)(findingsOf(s"""logger.info(s"x $${member.$name}")""").mkString should include("personal"))
      }
      findingsOf("""logger.info(s"password=${message.conversationId}")""").mkString should include("personal")
      findingsOf("""logger.info(s"x ${names.map(_.lastname).mkString}")""").mkString should include("personal")
    }
    "see a call over several lines and a parenthesis in the text, and not a comment or a plain string" in {
      findingsOf("logger.error(\n  s\"a ) b $member\",\n  e)") should have size 1
      findingsOf("// logger.info(s\"$member\")\n/* logger.info(s\"$member\") */") shouldBe empty
      findingsOf("""val a = "logger.info(s$member)"""") shouldBe empty
      findingsOf("""logger.info("not interpolated $member")""") shouldBe empty
    }
    "report file and line" in {
      logCalls("a/B.scala", "\n\nlogger.info(s\"$member\")").flatMap(findings).head should startWith("a/B.scala:3 ")
    }
  }
}
