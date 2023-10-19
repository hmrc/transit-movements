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
import uk.gov.hmrc.transitmovements.models.ItemCount
import uk.gov.hmrc.transitmovements.models.LocalReferenceNumber
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageSender
import uk.gov.hmrc.transitmovements.models.Movement
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.MovementType
import uk.gov.hmrc.transitmovements.models.PageNumber
import uk.gov.hmrc.transitmovements.models.PaginationMessageSummary
import uk.gov.hmrc.transitmovements.models.PaginationMovementSummary
import uk.gov.hmrc.transitmovements.models.TotalCount
import uk.gov.hmrc.transitmovements.models.UpdateMessageData
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoMessageMetadata
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoMessageMetadataAndBody
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoMovementSummary
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoPaginatedMessages
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoPaginatedMovements
import uk.gov.hmrc.transitmovements.models.mongo.write.MongoMessage
import uk.gov.hmrc.transitmovements.models.mongo.write.MongoMessageUpdateData
import uk.gov.hmrc.transitmovements.models.mongo.write.MongoMovement
import uk.gov.hmrc.transitmovements.repositories.MovementsRepository
import uk.gov.hmrc.transitmovements.services.errors.MongoError

import java.time.OffsetDateTime
import scala.annotation.nowarn
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@nowarn("msg=implicit numeric widening")
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

  "getMovementWithoutMessages" - {

    "getting a movement back from Mongo will perform the correct transformation" in forAll(arbitrary[MongoMovementSummary], arbitrary[MovementType]) {
      (mongoMovement, movementType) =>
        val (sut, movementsRepository) = createService()
        when(
          movementsRepository.getMovementWithoutMessages(
            EORINumber(eqTo(mongoMovement.enrollmentEORINumber.value)),
            MovementId(eqTo(mongoMovement._id.value)),
            eqTo(movementType)
          )
        ).thenReturn(EitherT.rightT[Future, MongoError](mongoMovement))

        whenReady(sut.getMovementWithoutMessages(mongoMovement.enrollmentEORINumber, mongoMovement._id, movementType).value) {
          result =>
            result mustBe Right(mongoMovement.asMovementWithoutMessages)
            verify(movementsRepository, times(1)).getMovementWithoutMessages(
              EORINumber(eqTo(mongoMovement.enrollmentEORINumber.value)),
              MovementId(eqTo(mongoMovement._id.value)),
              eqTo(movementType)
            )
            verifyNoMoreInteractions(movementsRepository)
        }
    }

    "failing to get a movement back from Mongo will return the error from the repository layer" in forAll(
      arbitrary[MongoMovementSummary],
      arbitrary[MovementType]
    ) {
      (mongoMovement, movementType) =>
        val (sut, movementsRepository) = createService()
        val error                      = MongoError.UnexpectedError(Some(new IllegalStateException()))
        when(
          movementsRepository.getMovementWithoutMessages(
            EORINumber(eqTo(mongoMovement.enrollmentEORINumber.value)),
            MovementId(eqTo(mongoMovement._id.value)),
            eqTo(movementType)
          )
        ).thenReturn(EitherT.leftT[Future, MongoMovementSummary](error))

        whenReady(sut.getMovementWithoutMessages(mongoMovement.enrollmentEORINumber, mongoMovement._id, movementType).value) {
          case Left(actual: MongoError.UnexpectedError) =>
            actual must be theSameInstanceAs error
            verify(movementsRepository, times(1)).getMovementWithoutMessages(
              EORINumber(eqTo(mongoMovement.enrollmentEORINumber.value)),
              MovementId(eqTo(mongoMovement._id.value)),
              eqTo(movementType)
            )
            verifyNoMoreInteractions(movementsRepository)
          case value => fail(s"Expected a Left of MongoError.UnexpectedError, got $value")
        }
    }
  }

  "getSingleMessage" - {

    "getting a movement back from Mongo will perform the correct transformation" in forAll(
      arbitrary[MovementId],
      arbitrary[EORINumber],
      arbitrary[MovementType],
      arbitrary[MongoMessageMetadataAndBody]
    ) {
      (movementId, eori, movementType, mongoMessage) =>
        val (sut, movementsRepository) = createService()
        when(
          movementsRepository.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(mongoMessage.id.value)),
            eqTo(movementType)
          )
        ).thenReturn(EitherT.rightT[Future, MongoError](mongoMessage))

        whenReady(sut.getSingleMessage(eori, movementId, mongoMessage.id, movementType).value) {
          result =>
            result mustBe Right(mongoMessage.asMessageResponse)
            verify(movementsRepository, times(1)).getSingleMessage(
              EORINumber(eqTo(eori.value)),
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(mongoMessage.id.value)),
              eqTo(movementType)
            )
            verifyNoMoreInteractions(movementsRepository)
        }
    }

    "failing to get a movement back from Mongo will return the error from the repository layer" in forAll(
      arbitrary[MovementId],
      arbitrary[EORINumber],
      arbitrary[MovementType],
      arbitrary[MongoMessageMetadataAndBody]
    ) {
      (movementId, eori, movementType, mongoMessage) =>
        val (sut, movementsRepository) = createService()
        val error                      = MongoError.UnexpectedError(Some(new IllegalStateException()))
        when(
          movementsRepository.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(mongoMessage.id.value)),
            eqTo(movementType)
          )
        ).thenReturn(EitherT.leftT[Future, MongoMessageMetadataAndBody](error))

        whenReady(sut.getSingleMessage(eori, movementId, mongoMessage.id, movementType).value) {
          case Left(actual: MongoError.UnexpectedError) =>
            actual must be theSameInstanceAs error
            verify(movementsRepository, times(1)).getSingleMessage(
              EORINumber(eqTo(eori.value)),
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(mongoMessage.id.value)),
              eqTo(movementType)
            )
            verifyNoMoreInteractions(movementsRepository)
          case value => fail(s"Expected a Left of MongoError.UnexpectedError, got $value")
        }
    }

  }

  "getMessages" - {

    "getting a series of messages back from Mongo will perform the correct transformation" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementId],
      arbitrary[MovementType],
      Gen
        .choose(1, 5)
        .flatMap(
          count => Gen.listOfN(count, arbitrary[MongoMessageMetadata])
        )
        .map(
          x => Vector(x: _*)
        )
    ) {
      (eori, movementId, movementType, messageList) =>
        // filters
        val page: Option[PageNumber]              = Gen.option(Gen.choose(1, 5).map(PageNumber.apply)).sample.get
        val count: Option[ItemCount]              = Gen.option(Gen.choose(25, 500).map(ItemCount.apply)).sample.get
        val received: Option[OffsetDateTime]      = Gen.option(arbitrary[OffsetDateTime]).sample.get
        val receivedUntil: Option[OffsetDateTime] = Gen.option(arbitrary[OffsetDateTime]).sample.get

        val (sut, movementsRepository) = createService()
        when(
          movementsRepository.getMessages(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            eqTo(movementType),
            eqTo(received),
            eqTo(page),
            eqTo(count),
            eqTo(receivedUntil)
          )
        ).thenReturn(
          EitherT.rightT[Future, MongoError](
            MongoPaginatedMessages(
              TotalCount(messageList.length),
              messageList
            )
          )
        )

        whenReady(sut.getMessages(eori, movementId, movementType, received, page, count, receivedUntil).value) {
          result =>
            result mustBe Right(
              PaginationMessageSummary(
                TotalCount(messageList.length),
                messageList.map(_.asMessageResponse)
              )
            )
            verify(movementsRepository, times(1)).getMessages(
              EORINumber(eqTo(eori.value)),
              MovementId(eqTo(movementId.value)),
              eqTo(movementType),
              eqTo(received),
              eqTo(page),
              eqTo(count),
              eqTo(receivedUntil)
            )
            verifyNoMoreInteractions(movementsRepository)
        }
    }

    "failing to get the list of messages from Mongo will return the error from the repository layer" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementId],
      arbitrary[MovementType]
    ) {
      (eori, movementId, movementType) =>
        // filters
        val page: Option[PageNumber]              = Gen.option(Gen.choose(1, 5).map(PageNumber.apply)).sample.get
        val count: Option[ItemCount]              = Gen.option(Gen.choose(25, 500).map(ItemCount.apply)).sample.get
        val received: Option[OffsetDateTime]      = Gen.option(arbitrary[OffsetDateTime]).sample.get
        val receivedUntil: Option[OffsetDateTime] = Gen.option(arbitrary[OffsetDateTime]).sample.get
        val error                                 = MongoError.UnexpectedError(Some(new IllegalStateException()))

        val (sut, movementsRepository) = createService()
        when(
          movementsRepository.getMessages(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            eqTo(movementType),
            eqTo(received),
            eqTo(page),
            eqTo(count),
            eqTo(receivedUntil)
          )
        ).thenReturn(EitherT.leftT[Future, MongoPaginatedMessages](error))

        whenReady(sut.getMessages(eori, movementId, movementType, received, page, count, receivedUntil).value) {
          case Left(actual: MongoError.UnexpectedError) =>
            actual must be theSameInstanceAs error
            verify(movementsRepository, times(1)).getMessages(
              EORINumber(eqTo(eori.value)),
              MovementId(eqTo(movementId.value)),
              eqTo(movementType),
              eqTo(received),
              eqTo(page),
              eqTo(count),
              eqTo(receivedUntil)
            )
            verifyNoMoreInteractions(movementsRepository)
          case value => fail(s"Expected a Left of MongoError.UnexpectedError, got $value")
        }
    }
  }

  "getMovements" - {

    "getting a series of movements back from Mongo will perform the correct transformation" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementType],
      Gen
        .choose(1, 5)
        .flatMap(
          count => Gen.listOfN(count, arbitrary[MongoMovementSummary])
        )
        .map(
          x => Vector(x: _*)
        )
    ) {
      (enrolmentEORI, movementType, movementList) =>
        // filters
        val page: Option[PageNumber]                                 = Gen.option(Gen.choose(1, 5).map(PageNumber.apply)).sample.get
        val count: Option[ItemCount]                                 = Gen.option(Gen.choose(25, 500).map(ItemCount.apply)).sample.get
        val received: Option[OffsetDateTime]                         = Gen.option(arbitrary[OffsetDateTime]).sample.get
        val receivedUntil: Option[OffsetDateTime]                    = Gen.option(arbitrary[OffsetDateTime]).sample.get
        val movementEori: Option[EORINumber]                         = Gen.option(arbitrary[EORINumber]).sample.get
        val localReferenceNumber: Option[LocalReferenceNumber]       = Gen.option(arbitrary[LocalReferenceNumber]).sample.get
        val movementReferenceNumber: Option[MovementReferenceNumber] = Gen.option(arbitrary[MovementReferenceNumber]).sample.get

        val (sut, movementsRepository) = createService()
        when(
          movementsRepository.getMovements(
            EORINumber(eqTo(enrolmentEORI.value)),
            eqTo(movementType),
            eqTo(received),
            eqTo(movementEori),
            eqTo(movementReferenceNumber),
            eqTo(page),
            eqTo(count),
            eqTo(receivedUntil),
            eqTo(localReferenceNumber)
          )
        ).thenReturn(
          EitherT.rightT[Future, MongoError](
            MongoPaginatedMovements(
              TotalCount(movementList.length),
              movementList
            )
          )
        )

        whenReady(
          sut.getMovements(enrolmentEORI, movementType, received, movementEori, movementReferenceNumber, page, count, receivedUntil, localReferenceNumber).value
        ) {
          result =>
            result mustBe Right(
              PaginationMovementSummary(
                TotalCount(movementList.length),
                movementList.map(_.asMovementWithoutMessages)
              )
            )
            verify(movementsRepository, times(1)).getMovements(
              EORINumber(eqTo(enrolmentEORI.value)),
              eqTo(movementType),
              eqTo(received),
              eqTo(movementEori),
              eqTo(movementReferenceNumber),
              eqTo(page),
              eqTo(count),
              eqTo(receivedUntil),
              eqTo(localReferenceNumber)
            )
            verifyNoMoreInteractions(movementsRepository)
        }
    }

    "failing to get the list of movements from Mongo will return the error from the repository layer" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementType]
    ) {
      (enrolmentEORI, movementType) =>
        // filters
        val page: Option[PageNumber]                                 = Gen.option(Gen.choose(1, 5).map(PageNumber.apply)).sample.get
        val count: Option[ItemCount]                                 = Gen.option(Gen.choose(25, 500).map(ItemCount.apply)).sample.get
        val received: Option[OffsetDateTime]                         = Gen.option(arbitrary[OffsetDateTime]).sample.get
        val receivedUntil: Option[OffsetDateTime]                    = Gen.option(arbitrary[OffsetDateTime]).sample.get
        val movementEori: Option[EORINumber]                         = Gen.option(arbitrary[EORINumber]).sample.get
        val localReferenceNumber: Option[LocalReferenceNumber]       = Gen.option(arbitrary[LocalReferenceNumber]).sample.get
        val movementReferenceNumber: Option[MovementReferenceNumber] = Gen.option(arbitrary[MovementReferenceNumber]).sample.get

        val error = MongoError.UnexpectedError(Some(new IllegalStateException()))

        val (sut, movementsRepository) = createService()
        when(
          movementsRepository.getMovements(
            EORINumber(eqTo(enrolmentEORI.value)),
            eqTo(movementType),
            eqTo(received),
            eqTo(movementEori),
            eqTo(movementReferenceNumber),
            eqTo(page),
            eqTo(count),
            eqTo(receivedUntil),
            eqTo(localReferenceNumber)
          )
        ).thenReturn(EitherT.leftT[Future, MongoPaginatedMovements](error))

        whenReady(
          sut.getMovements(enrolmentEORI, movementType, received, movementEori, movementReferenceNumber, page, count, receivedUntil, localReferenceNumber).value
        ) {
          case Left(actual: MongoError.UnexpectedError) =>
            actual must be theSameInstanceAs error
            verify(movementsRepository, times(1)).getMovements(
              EORINumber(eqTo(enrolmentEORI.value)),
              eqTo(movementType),
              eqTo(received),
              eqTo(movementEori),
              eqTo(movementReferenceNumber),
              eqTo(page),
              eqTo(count),
              eqTo(receivedUntil),
              eqTo(localReferenceNumber)
            )
            verifyNoMoreInteractions(movementsRepository)
          case value => fail(s"Expected a Left of MongoError.UnexpectedError, got $value")
        }
    }

  }

}
