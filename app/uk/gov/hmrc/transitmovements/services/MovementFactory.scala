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

package uk.gov.hmrc.transitmovements.services

import akka.stream.Materializer
import com.google.inject.ImplementedBy
import uk.gov.hmrc.transitmovements.models.values.ShortUUID
import uk.gov.hmrc.transitmovements.models.ArrivalData
import uk.gov.hmrc.transitmovements.models.DeclarationData
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.Movement
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementType

import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import javax.inject.Inject

@ImplementedBy(classOf[MovementFactoryImpl])
trait MovementFactory {

  def createDeparture(
    eori: EORINumber,
    movementType: MovementType,
    declarationData: DeclarationData,
    message: Message,
    created: OffsetDateTime,
    updated: OffsetDateTime
  ): Movement

  def createArrival(
    eori: EORINumber,
    movementType: MovementType,
    arrivalData: ArrivalData,
    message: Message,
    created: OffsetDateTime,
    updated: OffsetDateTime
  ): Movement

  def createEmptyMovement(eori: EORINumber, movementType: MovementType, created: OffsetDateTime, updated: OffsetDateTime): Movement

}

class MovementFactoryImpl @Inject() (
  clock: Clock,
  random: SecureRandom
)(implicit
  val materializer: Materializer
) extends MovementFactory {

  def createDeparture(
    eori: EORINumber,
    movementType: MovementType,
    declarationData: DeclarationData,
    message: Message,
    created: OffsetDateTime,
    updated: OffsetDateTime
  ): Movement =
    Movement(
      _id = MovementId(ShortUUID.next(clock, random)),
      enrollmentEORINumber = eori,
      movementType = movementType,
      movementEORINumber = Some(declarationData.movementEoriNumber),
      movementReferenceNumber = None,
      created = created,
      updated = updated,
      messages = Vector(message)
    )

  def createArrival(
    eori: EORINumber,
    movementType: MovementType,
    arrivalData: ArrivalData,
    message: Message,
    created: OffsetDateTime,
    updated: OffsetDateTime
  ): Movement =
    Movement(
      _id = MovementId(ShortUUID.next(clock, random)),
      enrollmentEORINumber = eori,
      movementType = movementType,
      movementEORINumber = Some(arrivalData.movementEoriNumber),
      movementReferenceNumber = Some(arrivalData.mrn),
      created = created,
      updated = updated,
      messages = Vector(message)
    )

  def createEmptyMovement(eori: EORINumber, movementType: MovementType, created: OffsetDateTime, updated: OffsetDateTime): Movement =
    Movement(
      _id = MovementId(ShortUUID.next(clock, random)),
      enrollmentEORINumber = eori,
      movementType = movementType,
      movementEORINumber = None,
      movementReferenceNumber = None,
      created = created,
      updated = updated,
      messages = Vector.empty[Message]
    )
}
