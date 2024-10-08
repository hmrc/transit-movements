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

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.transitmovements.base.SpecBase
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.generators.BaseGenerators
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.values.ShortUUID
import uk.gov.hmrc.transitmovements.models.ArrivalData
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.LocalReferenceNumber
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.ClientId
import uk.gov.hmrc.transitmovements.models.DeclarationData
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageSender
import uk.gov.hmrc.transitmovements.models.MovementType

import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

class MovementFactoryImplSpec
    extends SpecBase
    with ScalaFutures
    with Matchers
    with TestActorSystem
    with ModelGenerators
    with ScalaCheckDrivenPropertyChecks
    with BaseGenerators {

  val instant: OffsetDateTime = OffsetDateTime.of(2022, 5, 27, 11, 0, 0, 0, ZoneOffset.UTC)
  val clock: Clock            = Clock.fixed(instant.toInstant, ZoneOffset.UTC)
  val random                  = new SecureRandom
  val movementId: MovementId  = MovementId(ShortUUID.next(clock, random))

  "createDeparture" - {
    val sut = new MovementFactoryImpl(clock, random)

    "will create a departure with a message" in forAll(
      arbitrary[EORINumber],
      arbitrary[EORINumber],
      arbitrary[Message],
      arbitrary[LocalReferenceNumber],
      arbitrary[MessageSender],
      Gen.option(arbitrary[ClientId])
    ) {
      (enrollmentEori, movementEori, message, lrn, sender, clientId) =>
        val departure =
          sut.createDeparture(
            movementId,
            enrollmentEori,
            MovementType.Departure,
            DeclarationData(Some(movementEori), instant, lrn, sender),
            message,
            instant,
            instant,
            clientId,
            isTransitional = true
          )

        departure.messages.length mustBe 1
        departure.movementReferenceNumber mustBe None
        departure.localReferenceNumber mustBe Some(lrn)
        departure.messageSender mustBe Some(sender)
        departure.enrollmentEORINumber mustBe enrollmentEori
        departure.movementEORINumber mustBe Some(movementEori)
        departure.messages.head mustBe message
    }
  }

  "createArrival" - {
    val sut = new MovementFactoryImpl(clock, random)

    "will create a arrival with a message" in forAll(
      arbitrary[MovementReferenceNumber],
      arbitrary[EORINumber],
      arbitrary[EORINumber],
      arbitrary[Message],
      Gen.option(arbitrary[ClientId])
    ) {
      (mrn, enrollmentEori, movementEori, message, clientId) =>
        val arrival =
          sut.createArrival(
            movementId,
            enrollmentEori,
            MovementType.Arrival,
            ArrivalData(Some(movementEori), instant, mrn),
            message,
            instant,
            instant,
            clientId,
            isTransitional = true
          )

        arrival.messages.length mustBe 1
        arrival.movementReferenceNumber mustBe Some(mrn)
        arrival.enrollmentEORINumber mustBe enrollmentEori
        arrival.movementEORINumber mustBe Some(movementEori)
        arrival.messages.head mustBe message
        arrival.localReferenceNumber mustBe None
        arrival.messageSender mustBe None
    }
  }

  "createEmptyMovement" - {
    val sut = new MovementFactoryImpl(clock, random)

    "will create a movement with message and without movementEORINumber, movementReferenceNumber" in forAll(
      arbitrary[MovementType],
      arbitrary[EORINumber],
      arbitrary[Message],
      Gen.option(arbitrary[ClientId])
    ) {
      (movementType, enrollmentEori, message, clientId) =>
        val movement =
          sut.createEmptyMovement(enrollmentEori, movementType, message, instant, instant, clientId, isTransitional = true)

        movement.movementType mustBe movementType
        movement.messages.length mustBe 1
        movement.movementReferenceNumber mustBe None
        movement.enrollmentEORINumber mustBe enrollmentEori
        movement.movementEORINumber mustBe None
        movement.localReferenceNumber mustBe None
        movement.messageSender mustBe None
    }
  }
}
