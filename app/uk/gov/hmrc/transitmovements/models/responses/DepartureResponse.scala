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
import play.api.libs.json.Json
import play.api.libs.json.OFormat
import uk.gov.hmrc.transitmovements.models.Departure
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.formats.CommonFormats
import uk.gov.hmrc.transitmovements.models.formats.MongoFormats
import uk.gov.hmrc.transitmovements.models.formats.PresentationFormats

import java.time.OffsetDateTime

case class DepartureResponse(
  id: DepartureId,
  // movementReferenceNumber: Option[MovementReferenceNumber],
  created: OffsetDateTime,
  updated: OffsetDateTime
)

object DepartureResponse extends CommonFormats with MongoFormats {

  val projection: Bson =
    BsonDocument(
      "_id" -> 1,
      //   "movementReferenceNumber" -> 1,
      "created" -> 1,
      "updated" -> 1
    )
}
