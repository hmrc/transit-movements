package uk.gov.hmrc.transitmovements.models.mongo

import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.conversions.Bson
import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageStatus
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse

import java.net.URI
import java.time.OffsetDateTime

object MongoMessage {
  def from(message: Message): MongoMessage =
    MongoMessage(
      message.id,
      message.received,
      message.generated,
      message.messageType,
      message.triggerId,
      message.uri,
      message.body.map(SensitiveString),
      message.size,
      message.status
    )

  val simpleMetadataProjection: Bson =
    BsonDocument(
      "id" -> 1,
      "received" -> 1,
      "messageType" -> 1,
      "status" -> 1
    )

}
case class MongoMessage(
   id: MessageId,
   received: OffsetDateTime,
   generated: Option[OffsetDateTime],
   messageType: Option[MessageType],
   triggerId: Option[MessageId],
   uri: Option[URI],
   body: Option[SensitiveString],
   size: Option[Long],
   status: Option[MessageStatus]
 ) {

  @transient lazy val asMessageResponse: MessageResponse =
    MessageResponse(
      id,
      received,
      messageType,
      body.map(_.decryptedValue),
      status,
      uri
    )
}
