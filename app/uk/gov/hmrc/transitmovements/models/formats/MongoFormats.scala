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

package uk.gov.hmrc.transitmovements.models.formats

import play.api.libs.json.Format
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.Writes
import uk.gov.hmrc.mongo.play.json.formats.MongoBinaryFormats
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.formats.MongoUuidFormats
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.Movement
import uk.gov.hmrc.transitmovements.models.MovementWithoutMessages
import uk.gov.hmrc.transitmovements.models.PaginationMessageSummary
import uk.gov.hmrc.transitmovements.models.PaginationMovementSummary
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse

import java.time.OffsetDateTime
import java.time.ZoneOffset

trait MongoFormats extends CommonFormats with MongoBinaryFormats.Implicits with MongoJavatimeFormats.Implicits with MongoUuidFormats.Implicits {

  implicit val offsetDateTimeReads: Reads[OffsetDateTime] = Reads {
    value =>
      jatLocalDateTimeFormat
        .reads(value)
        .map(
          localDateTime => localDateTime.atOffset(ZoneOffset.UTC)
        )
  }

  implicit val offsetDateTimeWrites: Writes[OffsetDateTime] = Writes {
    value => jatLocalDateTimeFormat.writes(value.toLocalDateTime)
  }

  implicit val offsetDateTimeFormat: Format[OffsetDateTime] = Format.apply(offsetDateTimeReads, offsetDateTimeWrites)

  // these use the dates above, so need to be here for compile-time macro expansion
  implicit val messageFormat: Format[Message]                                     = Json.format[Message]
  implicit val movementFormat: Format[Movement]                                   = Json.format[Movement]
  implicit val movementWithoutMessagesFormat: Format[MovementWithoutMessages]     = Json.format[MovementWithoutMessages]
  implicit val messageResponseFormat: Format[MessageResponse]                     = Json.format[MessageResponse]
  implicit val paginationMovementSummaryFormat: Format[PaginationMovementSummary] = Json.format[PaginationMovementSummary]
  implicit val paginationMessageSummaryFormat: Format[PaginationMessageSummary]   = Json.format[PaginationMessageSummary]

}

object MongoFormats extends MongoFormats
