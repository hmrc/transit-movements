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

import play.api.libs.json.Json
import play.api.libs.json.OFormat

import java.time.OffsetDateTime

case class MovementWithoutMessages(
  _id: MovementId,
  enrollmentEORINumber: EORINumber,
  movementEORINumber: Option[EORINumber],
  movementReferenceNumber: Option[MovementReferenceNumber], // optional pending MRN allocation
  localReferenceNumber: Option[LocalReferenceNumber],
  created: OffsetDateTime,
  updated: OffsetDateTime,
  apiVersion: APIVersionHeader
)

object MovementWithoutMessages {
  implicit val format: OFormat[MovementWithoutMessages] = Json.format[MovementWithoutMessages]

  def fromMovement(movement: Movement) =
    MovementWithoutMessages(
      movement._id,
      movement.enrollmentEORINumber,
      movement.movementEORINumber,
      movement.movementReferenceNumber,
      movement.localReferenceNumber,
      movement.created,
      movement.updated,
      movement.apiVersion
    )
}
