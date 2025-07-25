/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.transitmovements.models.mongo.read

import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.conversions.Bson
import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageStatus
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse

import java.net.URI
import java.time.OffsetDateTime

object MongoMessageMetadataAndBody {

  val simpleMetadataProjection: Bson =
    BsonDocument(
      "id"          -> 1,
      "received"    -> 1,
      "messageType" -> 1,
      "status"      -> 1,
      "uri"         -> 1,
      "triggerId"   -> 1,
      "body"        -> 1
    )

}

case class MongoMessageMetadataAndBody(
  id: MessageId,
  received: OffsetDateTime,
  messageType: Option[MessageType],
  uri: Option[URI],
  body: Option[SensitiveString],
  triggerId: Option[MessageId],
  status: Option[MessageStatus]
) {

  @transient lazy val asMessageResponse: MessageResponse =
    MessageResponse(
      id,
      received,
      messageType,
      body.map(_.decryptedValue),
      status,
      triggerId,
      uri
    )
}
