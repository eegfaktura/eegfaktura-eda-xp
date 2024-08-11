package at.energydash.utils.zip

import java.util.zip.Checksum

class CRC8 extends Checksum {

  var poly: Int = 0x0D5
  var crc: Int = 0

  def update(v: Byte): Unit = {
    crc ^= v
    for (j <- 0 until 8) {
      if ((crc & 0x80) != 0) {
        crc = ((crc << 1) ^ poly)
      } else {
        crc <<= 1
      }
    }
    crc &= 0xFF
  }

  override def update (v: Int): Unit = {
    update(v.toByte)
  }

  override def update(buf: Array[Byte]): Unit =
    update(buf, 0, buf.length)

  def update(buf: Array[Byte], off: Int, nbytes: Int): Unit = {
    for (a <- 0 until nbytes) {
      update(buf(off+a))
    }
  }

  override def reset(): Unit = crc = 0

  override def getValue: Long = (crc & 0xFF)
}
