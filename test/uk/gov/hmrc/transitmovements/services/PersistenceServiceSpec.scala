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

import cats.data.EitherT
import org.mockito.ArgumentMatchers.{eq => eqTo}
import org.mockito.MockitoSugar
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.LocalReferenceNumber
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageSender
import uk.gov.hmrc.transitmovements.models.Movement
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.UpdateMessageData
import uk.gov.hmrc.transitmovements.models.mongo.MongoMessage
import uk.gov.hmrc.transitmovements.models.mongo.MongoMessageUpdateData
import uk.gov.hmrc.transitmovements.models.mongo.MongoMovement
import uk.gov.hmrc.transitmovements.repositories.MovementsRepository
import uk.gov.hmrc.transitmovements.services.errors.MongoError

import java.time.OffsetDateTime
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class PersistenceServiceSpec
    extends AnyFreeSpec
    with Matchers
    with MockitoSugar
    with BeforeAndAfterEach
    with ModelGenerators
    with ScalaCheckDrivenPropertyChecks
    with ScalaFutures {

  def createService(): (PersistenceService, MovementsRepository) = {
    val mockMovementsRepository = mock[MovementsRepository]
    (new PersistenceServiceImpl(mockMovementsRepository), mockMovementsRepository)
  }

  // It is assumed that each model has tested their conversions in their own unit tests, as a result,
  // they will not be tested here.

  // CREATE/UPDATE -- data to mongo is transformed

  "insertMovement" - {

    "inserting an arbitrary Movement submits the appropriate MongoMovement to the repository" in forAll(arbitrary[Movement]) {
      movement =>
        val (sut, movementsRepository) = createService()
        val mongoMovement              = MongoMovement.from(movement)
        when(movementsRepository.insert(eqTo(mongoMovement))).thenReturn(EitherT.rightT[Future, MongoError]((): Unit))

        whenReady(sut.insertMovement(movement).value) {
          result =>
            result mustBe Right((): Unit)
            verify(movementsRepository, times(1)).insert(eqTo(mongoMovement))
            verifyNoMoreInteractions(movementsRepository)
        }
    }

    "failing to insert an arbitrary Movement returns the same MongoError" in forAll(arbitrary[Movement]) {
      movement =>
        val (sut, movementsRepository) = createService()
        val mongoMovement              = MongoMovement.from(movement)
        val error                      = MongoError.UnexpectedError(Some(new IllegalStateException()))
        when(movementsRepository.insert(eqTo(mongoMovement))).thenReturn(EitherT.leftT[Future, Unit](error))

        whenReady(sut.insertMovement(movement).value) {
          case Left(actual: MongoError.UnexpectedError) =>
            actual must be theSameInstanceAs error
            verify(movementsRepository, times(1)).insert(eqTo(mongoMovement))
            verifyNoMoreInteractions(movementsRepository)
          case value => fail(s"Expected a Left of MongoError.UnexpectedError, got $value")
        }
    }

  }

  "updateMovement" - {

    "updating a movement submits the appropriate data to the repository" in forAll(
      arbitrary[MovementId],
      Gen.option(arbitrary[EORINumber]),
      Gen.option(arbitrary[MovementReferenceNumber]),
      Gen.option(arbitrary[LocalReferenceNumber]),
      Gen.option(arbitrary[MessageSender]),
      arbitrary[OffsetDateTime]
    ) {
      (movementId, movementEORI, mrn, lrn, messageSender, received) =>
        val (sut, movementsRepository) = createService()
        when(
          movementsRepository.updateMovement(MovementId(eqTo(movementId.value)), eqTo(movementEORI), eqTo(mrn), eqTo(lrn), eqTo(messageSender), eqTo(received))
        ).thenReturn(EitherT.rightT[Future, MongoError]((): Unit))

        whenReady(sut.updateMovement(movementId, movementEORI, mrn, lrn, messageSender, received).value) {
          result =>
            result mustBe Right((): Unit)
            verify(movementsRepository, times(1)).updateMovement(
              MovementId(eqTo(movementId.value)),
              eqTo(movementEORI),
              eqTo(mrn),
              eqTo(lrn),
              eqTo(messageSender),
              eqTo(received)
            )
            verifyNoMoreInteractions(movementsRepository)
        }
    }

    "failing to update a movement returns the same MongoError" in forAll(
      arbitrary[MovementId],
      Gen.option(arbitrary[EORINumber]),
      Gen.option(arbitrary[MovementReferenceNumber]),
      Gen.option(arbitrary[LocalReferenceNumber]),
      Gen.option(arbitrary[MessageSender]),
      arbitrary[OffsetDateTime]
    ) {
      (movementId, movementEORI, mrn, lrn, messageSender, received) =>
        val (sut, movementsRepository) = createService()
        val error                      = MongoError.UnexpectedError(Some(new IllegalStateException()))
        when(
          movementsRepository.updateMovement(MovementId(eqTo(movementId.value)), eqTo(movementEORI), eqTo(mrn), eqTo(lrn), eqTo(messageSender), eqTo(received))
        ).thenReturn(EitherT.leftT[Future, Unit](error))

        whenReady(sut.updateMovement(movementId, movementEORI, mrn, lrn, messageSender, received).value) {
          case Left(actual: MongoError.UnexpectedError) =>
            actual must be theSameInstanceAs error
            verify(movementsRepository, times(1)).updateMovement(
              MovementId(eqTo(movementId.value)),
              eqTo(movementEORI),
              eqTo(mrn),
              eqTo(lrn),
              eqTo(messageSender),
              eqTo(received)
            )
            verifyNoMoreInteractions(movementsRepository)
          case value => fail(s"Expected a Left of MongoError.UnexpectedError, got $value")
        }
    }
  }

  "attachMessage" - {

    "attaching an arbitrary Message submits the appropriate MongoMessage to the repository" in forAll(
      arbitrary[MovementId],
      arbitrary[Message],
      Gen.option(arbitrary[MovementReferenceNumber]),
      arbitrary[OffsetDateTime]
    ) {
      (movementId, message, mrn, received) =>
        val (sut, movementsRepository) = createService()
        val mongoMessage               = MongoMessage.from(message)
        when(
          movementsRepository.attachMessage(
            MovementId(eqTo(movementId.value)),
            eqTo(mongoMessage),
            eqTo(mrn),
            eqTo(received)
          )
        ).thenReturn(EitherT.rightT[Future, MongoError]((): Unit))

        whenReady(sut.attachMessage(movementId, message, mrn, received).value) {
          result =>
            result mustBe Right((): Unit)
            verify(movementsRepository, times(1)).attachMessage(
              MovementId(eqTo(movementId.value)),
              eqTo(mongoMessage),
              eqTo(mrn),
              eqTo(received)
            )
            verifyNoMoreInteractions(movementsRepository)
        }
    }

    "failing to attach an arbitrary MongoMessage returns the same MongoError" in forAll(
      arbitrary[MovementId],
      arbitrary[Message],
      Gen.option(arbitrary[MovementReferenceNumber]),
      arbitrary[OffsetDateTime]
    ) {
      (movementId, message, mrn, received) =>
        val (sut, movementsRepository) = createService()
        val mongoMessage               = MongoMessage.from(message)
        val error                      = MongoError.UnexpectedError(Some(new IllegalStateException()))
        when(
          movementsRepository.attachMessage(
            MovementId(eqTo(movementId.value)),
            eqTo(mongoMessage),
            eqTo(mrn),
            eqTo(received)
          )
        ).thenReturn(EitherT.leftT[Future, Unit](error))

        whenReady(sut.attachMessage(movementId, message, mrn, received).value) {
          case Left(actual: MongoError.UnexpectedError) =>
            actual must be theSameInstanceAs error
            verify(movementsRepository, times(1)).attachMessage(
              MovementId(eqTo(movementId.value)),
              eqTo(mongoMessage),
              eqTo(mrn),
              eqTo(received)
            )
            verifyNoMoreInteractions(movementsRepository)
          case value => fail(s"Expected a Left of MongoError.UnexpectedError, got $value")
        }
    }

  }

  "updateMessage" - {

    "updating a message submits the appropriate MongoMessageUpdateData to the repository" in forAll(
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[UpdateMessageData],
      arbitrary[OffsetDateTime]
    ) {
      (movementId, messageId, updateMessageData, received) =>
        val (sut, movementsRepository) = createService()
        val mongoMessageUpdateData     = MongoMessageUpdateData.from(updateMessageData)
        when(
          movementsRepository.updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(mongoMessageUpdateData),
            eqTo(received)
          )
        ).thenReturn(EitherT.rightT[Future, MongoError]((): Unit))

        whenReady(sut.updateMessage(movementId, messageId, updateMessageData, received).value) {
          result =>
            result mustBe Right((): Unit)
            verify(movementsRepository, times(1)).updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              eqTo(mongoMessageUpdateData),
              eqTo(received)
            )
            verifyNoMoreInteractions(movementsRepository)
        }
    }

    "failing to update a message returns the same MongoError" in forAll(
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[UpdateMessageData],
      arbitrary[OffsetDateTime]
    ) {
      (movementId, messageId, updateMessageData, received) =>
        val (sut, movementsRepository) = createService()
        val mongoMessageUpdateData     = MongoMessageUpdateData.from(updateMessageData)
        val error                      = MongoError.UnexpectedError(Some(new IllegalStateException()))
        when(
          movementsRepository.updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(mongoMessageUpdateData),
            eqTo(received)
          )
        ).thenReturn(EitherT.leftT[Future, Unit](error))

        whenReady(sut.updateMessage(movementId, messageId, updateMessageData, received).value) {
          case Left(actual: MongoError.UnexpectedError) =>
            actual must be theSameInstanceAs error
            verify(movementsRepository, times(1)).updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              eqTo(mongoMessageUpdateData),
              eqTo(received)
            )
            verifyNoMoreInteractions(movementsRepository)
          case value => fail(s"Expected a Left of MongoError.UnexpectedError, got $value")
        }
    }

  }

  // GET -- data from Mongo is transformed

  "getMovementWithoutMessages" - {}

  "getSingleMessage" - {}

  "getMessages" - {}

  "getMovements" - {}

}
