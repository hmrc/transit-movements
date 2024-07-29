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

import uk.gov.hmrc.transitmovements.models.requests.common.EORINumber
import uk.gov.hmrc.transitmovements.models.requests.common.LocalReferenceNumber
import uk.gov.hmrc.transitmovements.models.requests.common.MovementReferenceNumber

import java.time.OffsetDateTime

sealed trait ExtractedData {
  def movementEoriNumber: Option[EORINumber]
  def generationDate: OffsetDateTime
  def movementReferenceNumber: Option[MovementReferenceNumber]
  def localReferenceNumber: Option[LocalReferenceNumber]
  def messageSender: Option[MessageSender]
}

case class ArrivalData(movementEoriNumber: Option[EORINumber], generationDate: OffsetDateTime, mrn: MovementReferenceNumber) extends ExtractedData {
  lazy val movementReferenceNumber = Some(mrn)
  lazy val localReferenceNumber    = None
  lazy val messageSender           = None
}

case class DeclarationData(
  movementEoriNumber: Option[EORINumber],
  generationDate: OffsetDateTime,
  lrn: LocalReferenceNumber,
  sender: MessageSender
) extends ExtractedData {
  lazy val movementReferenceNumber = None
  lazy val localReferenceNumber    = Some(lrn)
  lazy val messageSender           = Some(sender)

}
