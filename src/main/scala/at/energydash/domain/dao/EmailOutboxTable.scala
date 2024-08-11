package at.energydash.domain.dao

import slick.lifted.ProvenShape
import slick.sql.SqlProfile.ColumnOption.SqlType

import java.sql.Timestamp

trait EmailOutboxTable {

  import PostgresProfiler.api._

  class EmailOutboxs(tag: Tag) extends Table[EmailOutbox](tag, Some("eda"), "inbox") {
    def id: Rep[Long] = column[Long]("id", SqlType("SERIAL"),  O.PrimaryKey,  O.AutoInc)
    def subject: Rep[String] = column[String]("subject")
    def tenant: Rep[String] = column[String]("tenant")
    def content: Rep[Array[Byte]] = column[Array[Byte]]("content")
    def received: Rep[Timestamp] = column[Timestamp]("received")
    def * : ProvenShape[EmailOutbox] = (id.?, tenant, subject, content, received) <> (EmailOutbox.tupled, EmailOutbox.unapply)
  }

  val emailOutboxs = TableQuery[EmailOutboxs]
}