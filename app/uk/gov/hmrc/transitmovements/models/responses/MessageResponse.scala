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

package uk.gov.hmrc.transitmovements.models.responses

import play.api.libs.json.Format
import play.api.libs.json.Json
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageStatus
import uk.gov.hmrc.transitmovements.models.MessageType

import java.net.URI
import java.time.OffsetDateTime

case class MessageResponse(
  id: MessageId,
  received: OffsetDateTime,
  messageType: Option[MessageType],
  body: Option[String],
  status: Option[MessageStatus],
  triggerId: Option[MessageId],
  uri: Option[URI] = None
)

object MessageResponse {

  implicit val format: Format[MessageResponse] = Json.format[MessageResponse]

  def fromMessageWithBody(message: Message) =
    MessageResponse(
      message.id,
      message.received,
      message.messageType,
      message.body,
      message.status,
      message.triggerId,
      None
    )

  def fromMessageWithoutBody(message: Message) =
    MessageResponse(
      message.id,
      message.received,
      message.messageType,
      None,
      message.status,
      message.triggerId,
      message.uri
    )
}
