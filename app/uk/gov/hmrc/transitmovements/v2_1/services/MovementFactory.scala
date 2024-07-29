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

package uk.gov.hmrc.transitmovements.v2_1.services

import com.google.inject.ImplementedBy
import org.apache.pekko.stream.Materializer
import uk.gov.hmrc.transitmovements.models.requests.common.EORINumber
import uk.gov.hmrc.transitmovements.models.requests.common.MovementId
import uk.gov.hmrc.transitmovements.v2_1.models._
import uk.gov.hmrc.transitmovements.v2_1.models.values.ShortUUID

import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import javax.inject.Inject

@ImplementedBy(classOf[MovementFactoryImpl])
trait MovementFactory {

  def generateId(): MovementId

  def createDeparture(
    movementId: MovementId,
    eori: EORINumber,
    movementType: MovementType,
    declarationData: DeclarationData,
    message: Message,
    created: OffsetDateTime,
    updated: OffsetDateTime
  ): Movement

  def createArrival(
    movementId: MovementId,
    eori: EORINumber,
    movementType: MovementType,
    arrivalData: ArrivalData,
    message: Message,
    created: OffsetDateTime,
    updated: OffsetDateTime
  ): Movement

  def createEmptyMovement(eori: EORINumber, movementType: MovementType, message: Message, created: OffsetDateTime, updated: OffsetDateTime): Movement

}

class MovementFactoryImpl @Inject() (
  clock: Clock,
  random: SecureRandom
)(implicit
  val materializer: Materializer
) extends MovementFactory {

  def generateId(): MovementId = MovementId(ShortUUID.next(clock, random))

  def createDeparture(
    movementId: MovementId,
    eori: EORINumber,
    movementType: MovementType,
    declarationData: DeclarationData,
    message: Message,
    created: OffsetDateTime,
    updated: OffsetDateTime
  ): Movement =
    Movement(
      _id = movementId,
      enrollmentEORINumber = eori,
      movementType = movementType,
      movementEORINumber = declarationData.movementEoriNumber,
      movementReferenceNumber = None,
      localReferenceNumber = Some(declarationData.lrn),
      messageSender = Some(declarationData.sender),
      created = created,
      updated = updated,
      messages = Vector(message)
    )

  def createArrival(
    movementId: MovementId,
    eori: EORINumber,
    movementType: MovementType,
    arrivalData: ArrivalData,
    message: Message,
    created: OffsetDateTime,
    updated: OffsetDateTime
  ): Movement =
    Movement(
      _id = movementId,
      enrollmentEORINumber = eori,
      movementType = movementType,
      movementEORINumber = arrivalData.movementEoriNumber,
      movementReferenceNumber = Some(arrivalData.mrn),
      localReferenceNumber = None,
      messageSender = None,
      created = created,
      updated = updated,
      messages = Vector(message)
    )

  def createEmptyMovement(eori: EORINumber, movementType: MovementType, message: Message, created: OffsetDateTime, updated: OffsetDateTime): Movement =
    Movement(
      _id = generateId(),
      enrollmentEORINumber = eori,
      movementType = movementType,
      movementEORINumber = None,
      movementReferenceNumber = None,
      localReferenceNumber = None,
      messageSender = None,
      created = created,
      updated = updated,
      messages = Vector(message)
    )

}
