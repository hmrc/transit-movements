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

import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.LocalReferenceNumber
import uk.gov.hmrc.transitmovements.models.MessageSender
import uk.gov.hmrc.transitmovements.models.Movement
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.MovementType

import java.time.OffsetDateTime

object MongoMovement {

  def from(movement: Movement): MongoMovement =
    MongoMovement(
      movement._id,
      movement.movementType,
      movement.enrollmentEORINumber,
      movement.movementEORINumber,
      movement.movementReferenceNumber,
      movement.localReferenceNumber,
      movement.messageSender,
      movement.created,
      movement.updated,
      movement.messages.map(MongoMessage.from)
    )

}

case class MongoMovement(
  _id: MovementId,
  movementType: MovementType,
  enrollmentEORINumber: EORINumber,
  movementEORINumber: Option[EORINumber],
  movementReferenceNumber: Option[MovementReferenceNumber], // optional pending MRN allocation
  localReferenceNumber: Option[LocalReferenceNumber],
  messageSender: Option[MessageSender],
  created: OffsetDateTime,
  updated: OffsetDateTime,
  messages: Vector[MongoMessage]
)
