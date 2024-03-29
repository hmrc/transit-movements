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

package uk.gov.hmrc.transitmovements.matchers

import org.mockito.ArgumentMatcher
import uk.gov.hmrc.transitmovements.models.MessageStatus
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.ObjectStoreURI
import uk.gov.hmrc.transitmovements.models.UpdateMessageData

import java.time.OffsetDateTime

case class UpdateMessageDataMatcher(
  objectStoreURI: Option[ObjectStoreURI] = None,
  body: Option[String] = None,
  status: MessageStatus,
  messageType: Option[MessageType] = None,
  generationDate: Option[OffsetDateTime] = None,
  expectSize: Boolean = true
) extends ArgumentMatcher[UpdateMessageData] {

  // intentionally ignores size, other than to check it's there
  override def matches(argument: UpdateMessageData): Boolean =
    argument.body == body &&
      argument.messageType == messageType &&
      argument.status == status &&
      argument.objectStoreURI == objectStoreURI &&
      argument.generationDate == generationDate &&
      argument.size.isDefined == expectSize
}
