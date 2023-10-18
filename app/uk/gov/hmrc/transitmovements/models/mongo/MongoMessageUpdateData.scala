package uk.gov.hmrc.transitmovements.models.mongo

import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.transitmovements.models.MessageStatus
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.ObjectStoreURI
import uk.gov.hmrc.transitmovements.models.UpdateMessageData

import java.time.OffsetDateTime

object MongoMessageUpdateData {
  def from(updateMessageData: UpdateMessageData): MongoMessageUpdateData =
    MongoMessageUpdateData(
      updateMessageData.objectStoreURI,
      updateMessageData.body.map(SensitiveString),
      updateMessageData.size,
      updateMessageData.status,
      updateMessageData.messageType,
      updateMessageData.generationDate
    )
}

case class MongoMessageUpdateData(
   objectStoreURI: Option[ObjectStoreURI],
   body: Option[SensitiveString],
   size: Option[Long],
   status: MessageStatus,
   messageType: Option[MessageType],
   generationDate: Option[OffsetDateTime]
 )
