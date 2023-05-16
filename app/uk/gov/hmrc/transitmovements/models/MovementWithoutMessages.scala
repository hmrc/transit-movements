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

import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.conversions.Bson

import java.time.OffsetDateTime

case class MovementWithoutMessages(
  _id: MovementId,
  enrollmentEORINumber: EORINumber,
  movementEORINumber: Option[EORINumber],
  movementReferenceNumber: Option[MovementReferenceNumber], // optional pending MRN allocation
  movementLRN: Option[LocalReferenceNumber],
  created: OffsetDateTime,
  updated: OffsetDateTime
)

object MovementWithoutMessages {

  val projection: Bson =
    BsonDocument(
      "_id"                     -> 1,
      "enrollmentEORINumber"    -> 1,
      "movementEORINumber"      -> 1,
      "movementReferenceNumber" -> 1,
      "movementLRN"             -> 1,
      "created"                 -> 1,
      "updated"                 -> 1
    )

  def fromMovement(movement: Movement) =
    MovementWithoutMessages(
      movement._id,
      movement.enrollmentEORINumber,
      movement.movementEORINumber,
      movement.movementReferenceNumber,
      movement.movementLRN,
      movement.created,
      movement.updated
    )
}
