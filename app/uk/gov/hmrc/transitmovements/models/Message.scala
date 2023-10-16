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

package uk.gov.hmrc.transitmovements.models

import uk.gov.hmrc.crypto.Sensitive.SensitiveString

import java.net.URI
import java.time.OffsetDateTime

case class Message(
  id: MessageId,
  received: OffsetDateTime,
  generated: Option[OffsetDateTime],
  messageType: Option[MessageType],
  triggerId: Option[MessageId],
  uri: Option[URI],
  body: Option[SensitiveString],
  size: Option[Long],
  status: Option[MessageStatus]
)
