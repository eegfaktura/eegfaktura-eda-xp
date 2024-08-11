package at.energydash.domain.dao

import slick.lifted.ProvenShape

trait EegMasterTable {

  import PostgresProfiler.api._

  class EegMasters(tag: Tag) extends Table[EegMaster](tag, Some("base"), "eeg") {
    def tenant: Rep[String] = column[String]("tenant")
    def communityId: Rep[String] = column[String]("communityId")
    def * : ProvenShape[EegMaster] = (tenant, communityId) <> (EegMaster.tupled, EegMaster.unapply)
  }

  val eegMasters = TableQuery[EegMasters]

}
