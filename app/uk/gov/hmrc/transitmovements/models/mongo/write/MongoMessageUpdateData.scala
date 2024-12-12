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

package uk.gov.hmrc.transitmovements.models.mongo.write

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
      updateMessageData.body.map(SensitiveString.apply),
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
