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

package uk.gov.hmrc.transitmovements.v2_1.models

import uk.gov.hmrc.transitmovements.models.requests.common.EORINumber
import uk.gov.hmrc.transitmovements.models.requests.common.LocalReferenceNumber
import uk.gov.hmrc.transitmovements.models.requests.common.MovementId
import uk.gov.hmrc.transitmovements.models.requests.common.MovementReferenceNumber

import java.time.OffsetDateTime

case class Movement(
  _id: MovementId,
  movementType: MovementType,
  enrollmentEORINumber: EORINumber,
  movementEORINumber: Option[EORINumber],
  movementReferenceNumber: Option[MovementReferenceNumber], // optional pending MRN allocation
  localReferenceNumber: Option[LocalReferenceNumber],
  messageSender: Option[MessageSender],
  created: OffsetDateTime,
  updated: OffsetDateTime,
  messages: Vector[Message]
)
