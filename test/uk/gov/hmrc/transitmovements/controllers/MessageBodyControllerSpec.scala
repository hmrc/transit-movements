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

package uk.gov.hmrc.transitmovements.controllers

import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.{eq => eqTo}
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoSugar.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterEach
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.FakeRequest
import play.api.test.Helpers.contentAsJson
import play.api.test.Helpers.contentAsString
import play.api.test.Helpers.defaultAwaitTimeout
import play.api.test.Helpers.status
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.transitmovements.base.SpecBase
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageStatus
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementType
import uk.gov.hmrc.transitmovements.models.ObjectStoreResourceLocation
import uk.gov.hmrc.transitmovements.models.formats.PresentationFormats
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse
import uk.gov.hmrc.transitmovements.repositories.MovementsRepository
import uk.gov.hmrc.transitmovements.services.ObjectStoreService
import uk.gov.hmrc.transitmovements.services.errors.MongoError
import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError

import java.net.URI
import java.time.OffsetDateTime
import scala.annotation.nowarn
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@nowarn("msg=dead code following this construct")
class MessageBodyControllerSpec
    extends SpecBase
    with Matchers
    with TestActorSystem
    with OptionValues
    with ScalaFutures
    with BeforeAndAfterEach
    with PresentationFormats
    with ModelGenerators
    with ScalaCheckDrivenPropertyChecks {

  private val now = OffsetDateTime.now()

  "getBody" - {

    "getting a small message" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementType],
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[MessageType],
      Gen
        .stringOfN(15, Gen.alphaNumChar)
        .map(
          s => s"<test>$s</test>"
        )
    ) {
      (eori, movementType, movementId, messageId, messageType, xml) =>
        val mockMovementsRepo = mock[MovementsRepository]
        when(
          mockMovementsRepo.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(
            EitherT(
              Future.successful[Either[MongoError, Option[MessageResponse]]](
                Right(
                  Some(
                    MessageResponse(
                      messageId,
                      now,
                      messageType,
                      Some(xml),
                      Some(MessageStatus.Success),
                      None
                    )
                  )
                )
              )
            )
          )

        val mockObjectStoreService = mock[ObjectStoreService]

        val sut                    = new MessageBodyController(stubControllerComponents(), mockMovementsRepo, mockObjectStoreService)
        val result: Future[Result] = sut.getBody(eori, movementType, movementId, messageId)(FakeRequest("GET", "/"))

        status(result) mustBe OK
        contentAsString(result) mustBe s"<test>$xml</test>"

        whenReady(result) {
          _ =>
            verify(mockMovementsRepo, times(1))
              .getSingleMessage(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(movementType))
            verify(mockObjectStoreService, times(0))
              .getObjectStoreFile(ObjectStoreResourceLocation(anyString()))(any(), any()) // any ensures it's never called in any way
        }
    }

    "getting a large message" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementType],
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[MessageType],
      Gen
        .stringOfN(15, Gen.alphaNumChar)
        .map(
          s => s"<test>$s</test>"
        )
    ) {
      (eori, movementType, movementId, messageId, messageType, xml) =>
        val objectStoreUri = testObjectStoreURI(movementId, messageId, now)

        val mockMovementsRepo = mock[MovementsRepository]
        when(
          mockMovementsRepo.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(
            EitherT(
              Future.successful[Either[MongoError, Option[MessageResponse]]](
                Right(
                  Some(
                    MessageResponse(
                      messageId,
                      now,
                      messageType,
                      None,
                      Some(MessageStatus.Success),
                      Some(new URI(objectStoreUri.value))
                    )
                  )
                )
              )
            )
          )

        val mockObjectStoreService = mock[ObjectStoreService]
        when(mockObjectStoreService.getObjectStoreFile(ObjectStoreResourceLocation(eqTo(objectStoreUri.asResourceLocation.get.value)))(any(), any()))
          .thenReturn(EitherT.rightT(Source.single(ByteString(xml))))

        val sut                    = new MessageBodyController(stubControllerComponents(), mockMovementsRepo, mockObjectStoreService)
        val result: Future[Result] = sut.getBody(eori, movementType, movementId, messageId)(FakeRequest("GET", "/"))

        status(result) mustBe OK
        contentAsString(result) mustBe s"<test>$xml</test>"

        whenReady(result) {
          _ =>
            verify(mockMovementsRepo, times(1))
              .getSingleMessage(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(movementType))
            verify(mockObjectStoreService, times(1))
              .getObjectStoreFile(ObjectStoreResourceLocation(eqTo(objectStoreUri.asResourceLocation.get.value)))(any(), any())
        }
    }

    "no message on pending message returns not found" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementType],
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[MessageType]
    ) {
      (eori, movementType, movementId, messageId, messageType) =>
        val mockMovementsRepo = mock[MovementsRepository]
        when(
          mockMovementsRepo.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(
            EitherT.rightT[Future, MongoError](
              Some(
                MessageResponse(
                  messageId,
                  now,
                  messageType,
                  None,
                  Some(MessageStatus.Pending),
                  None
                )
              )
            )
          )

        val mockObjectStoreService = mock[ObjectStoreService]

        val sut                    = new MessageBodyController(stubControllerComponents(), mockMovementsRepo, mockObjectStoreService)
        val result: Future[Result] = sut.getBody(eori, movementType, movementId, messageId)(FakeRequest("GET", "/"))

        status(result) mustBe NOT_FOUND
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "NOT_FOUND",
          "message" -> s"Body of message ID ${messageId.value} for movement ID ${movementId.value} was not found"
        )

        whenReady(result) {
          _ =>
            verify(mockMovementsRepo, times(1))
              .getSingleMessage(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(movementType))
            verify(mockObjectStoreService, times(0))
              .getObjectStoreFile(ObjectStoreResourceLocation(any()))(any(), any()) // any ensures it's never called in any way
        }
    }

    "no message on non-existing movement ID returns not found" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementType],
      arbitrary[MovementId],
      arbitrary[MessageId]
    ) {
      (eori, movementType, movementId, messageId) =>
        val mockMovementsRepo = mock[MovementsRepository]
        when(
          mockMovementsRepo.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(EitherT.leftT[Future, Option[MessageResponse]](MongoError.DocumentNotFound("not found")))

        val mockObjectStoreService = mock[ObjectStoreService]

        val sut                    = new MessageBodyController(stubControllerComponents(), mockMovementsRepo, mockObjectStoreService)
        val result: Future[Result] = sut.getBody(eori, movementType, movementId, messageId)(FakeRequest("GET", "/"))

        status(result) mustBe NOT_FOUND
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "NOT_FOUND",
          "message" -> s"not found"
        )

        whenReady(result) {
          _ =>
            verify(mockMovementsRepo, times(1))
              .getSingleMessage(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(movementType))
            verify(mockObjectStoreService, times(0))
              .getObjectStoreFile(ObjectStoreResourceLocation(any()))(any(), any()) // any ensures it's never called in any way
        }

    }

    "no message on non-existing message ID returns not found" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementType],
      arbitrary[MovementId],
      arbitrary[MessageId]
    ) {
      (eori, movementType, movementId, messageId) =>
        val mockMovementsRepo = mock[MovementsRepository]
        when(
          mockMovementsRepo.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(EitherT.rightT[Future, MongoError](None))

        val mockObjectStoreService = mock[ObjectStoreService]

        val sut                    = new MessageBodyController(stubControllerComponents(), mockMovementsRepo, mockObjectStoreService)
        val result: Future[Result] = sut.getBody(eori, movementType, movementId, messageId)(FakeRequest("GET", "/"))

        status(result) mustBe NOT_FOUND
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "NOT_FOUND",
          "message" -> s"Body of message ID ${messageId.value} for movement ID ${movementId.value} was not found"
        )

        whenReady(result) {
          _ =>
            verify(mockMovementsRepo, times(1))
              .getSingleMessage(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(movementType))
            verify(mockObjectStoreService, times(0))
              .getObjectStoreFile(ObjectStoreResourceLocation(any()))(any(), any()) // any ensures it's never called in any way
        }

    }

    "existing message with no object store object at provided URI returns 500" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementType],
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[MessageType]
    ) {
      (eori, movementType, movementId, messageId, messageType) =>
        val objectStoreUri = testObjectStoreURI(movementId, messageId, now)

        val mockMovementsRepo = mock[MovementsRepository]
        when(
          mockMovementsRepo.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(
            EitherT.rightT[Future, MongoError](
              Some(
                MessageResponse(
                  messageId,
                  now,
                  messageType,
                  None,
                  Some(MessageStatus.Success),
                  Some(new URI(objectStoreUri.value))
                )
              )
            )
          )

        val mockObjectStoreService = mock[ObjectStoreService]
        when(mockObjectStoreService.getObjectStoreFile(ObjectStoreResourceLocation(eqTo(objectStoreUri.asResourceLocation.get.value)))(any(), any()))
          .thenReturn(EitherT.leftT(ObjectStoreError.FileNotFound("...")))

        val sut                    = new MessageBodyController(stubControllerComponents(), mockMovementsRepo, mockObjectStoreService)
        val result: Future[Result] = sut.getBody(eori, movementType, movementId, messageId)(FakeRequest("GET", "/"))

        status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> s"file not found at location: ..."
        )

        whenReady(result) {
          _ =>
            verify(mockMovementsRepo, times(1))
              .getSingleMessage(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(movementType))
            verify(mockObjectStoreService, times(1))
              .getObjectStoreFile(ObjectStoreResourceLocation(eqTo(objectStoreUri.asResourceLocation.get.value)))(any(), any())
        }

    }
  }

}
