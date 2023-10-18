package uk.gov.hmrc.transitmovements.models.mongo

import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.conversions.Bson
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.LocalReferenceNumber
import uk.gov.hmrc.transitmovements.models.MessageSender
import uk.gov.hmrc.transitmovements.models.Movement
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.MovementType
import uk.gov.hmrc.transitmovements.models.MovementWithoutMessages

import java.time.OffsetDateTime

object MongoMovement {
  def from(movement: Movement): MongoMovement =
    MongoMovement(
      movement._id,
      movement.movementType,
      movement.enrollmentEORINumber,
      movement.movementEORINumber,
      movement.movementReferenceNumber,
      movement.localReferenceNumber,
      movement.messageSender,
      movement.created,
      movement.updated,
      Some(movement.messages.map(MongoMessage.from))
    )

  val withoutMovementsProjection: Bson =
    BsonDocument(
      "_id" -> 1,
      "enrollmentEORINumber" -> 1,
      "movementEORINumber" -> 1,
      "movementReferenceNumber" -> 1,
      "localReferenceNumber" -> 1,
      "created" -> 1,
      "updated" -> 1
    )

}
case class MongoMovement (
   _id: MovementId,
   movementType: MovementType,
   enrollmentEORINumber: EORINumber,
   movementEORINumber: Option[EORINumber],
   movementReferenceNumber: Option[MovementReferenceNumber], // optional pending MRN allocation
   localReferenceNumber: Option[LocalReferenceNumber],
   messageSender: Option[MessageSender],
   created: OffsetDateTime,
   updated: OffsetDateTime,
   messages: Option[Vector[MongoMessage]]
 ) {

  @transient lazy val asMovementWithoutMessages: MovementWithoutMessages =
    MovementWithoutMessages(
      _id,
      enrollmentEORINumber,
      movementEORINumber,
      movementReferenceNumber,
      localReferenceNumber,
      created,
      updated
    )
}
