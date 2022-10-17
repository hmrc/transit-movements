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

package uk.gov.hmrc.transitmovements.models.formats

import play.api.libs.json.Format
import play.api.libs.json.Json
import uk.gov.hmrc.transitmovements.models.Movement
import uk.gov.hmrc.transitmovements.models.DepartureWithoutMessages
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse

trait PresentationFormats extends CommonFormats {

  // as these use the OffsetDateTime times, these need to be here to avoid the macro expansion of
  // OffsetDateTime when used with Mongo.
  implicit val messageFormat: Format[Message]                                   = Json.format[Message]
  implicit val movementFormat: Format[Movement]                                 = Json.format[Movement]
  implicit val departureWithoutMessagesFormat: Format[DepartureWithoutMessages] = Json.format[DepartureWithoutMessages]
  implicit val messageResponseFormat: Format[MessageResponse]                   = Json.format[MessageResponse]

}

object PresentationFormats extends PresentationFormats
