/*
 * Copyright 2022 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.transitmovements.models.responses

import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.conversions.Bson
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageType
import java.net.URI
import java.time.OffsetDateTime

case class MessageResponse(
  _id: DepartureId,
  id: MessageId,
  received: OffsetDateTime,
  generated: OffsetDateTime,
  messageType: MessageType,
  triggerId: Option[MessageId],
  url: Option[URI],
  body: Option[String]
)

object MessageResponse {

  val projection: Bson =
    BsonDocument(
      "_id"         -> 1,
      "id"          -> 1,
      "received"    -> 1,
      "generated"   -> 1,
      "messageType" -> 1,
      "triggerId"   -> 1,
      "url"         -> 1,
      "body"        -> 1
    )

  def fromMessage(departureId: DepartureId, message: Message) =
    MessageResponse(
      departureId,
      message.id,
      message.received,
      message.generated,
      message.messageType,
      message.triggerId,
      message.url,
      message.body
    )
}
