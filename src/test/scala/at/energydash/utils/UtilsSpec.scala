package at.energydash.utils

import at.energydash.utils.zip.CRC8
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class UtilsSpec extends AnyWordSpec with Matchers {
  "Base58" should {
    "encode with the Bitcoin alphabet (known vector) and round-trip" in {
      Base58.encode("Hello World".getBytes("US-ASCII")) shouldBe "JxF12TrwUP45BMd"
      Base58.decode("JxF12TrwUP45BMd").toSeq shouldBe "Hello World".getBytes("US-ASCII").toSeq
    }
    "keep leading zero bytes as '1'" in {
      Base58.encode(Seq[Byte](0, 0, 1)) shouldBe "112"
      Base58.decode("112").toSeq shouldBe Seq[Byte](0, 0, 1)
      Base58.encode(Seq.empty) shouldBe ""
    }
  }

  "CRC8" should {
    "be CRC-8 with polynomial 0xD5 (check value of \"123456789\" is 0xBC)" in {
      val c = new CRC8
      c.update("123456789".getBytes("US-ASCII"))
      c.getValue shouldBe 0xBC
      c.reset()
      c.getValue shouldBe 0
    }
    "handle bytes with the high bit set" in {
      val c = new CRC8
      c.update(Array[Byte](-1, -128, 127))
      c.getValue should (be >= 0L and be <= 255L)
    }
  }
}
