package at.energydash.service

import akka.http.scaladsl.model.Multipart

case class FileInfo(bodyPart: Multipart.BodyPart, processName: String) {
}

object FileInfo {
  def apply (part: Multipart.FormData.BodyPart): FileInfo = {
    new FileInfo(part, part.name)
  }
}
