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
import org.mockito.ArgumentMatchers.argThat
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
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.CONFLICT
import play.api.http.Status.CREATED
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
import play.api.libs.Files.SingletonTemporaryFileCreator
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers.contentAsJson
import play.api.test.Helpers.contentAsString
import play.api.test.Helpers.defaultAwaitTimeout
import play.api.test.Helpers.status
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.transitmovements.base.SpecBase
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.matchers.UpdateMessageDataMatcher
import uk.gov.hmrc.transitmovements.models.ArrivalData
import uk.gov.hmrc.transitmovements.models.BodyStorage
import uk.gov.hmrc.transitmovements.models.DeclarationData
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.ExtractedData
import uk.gov.hmrc.transitmovements.models.LocalReferenceNumber
import uk.gov.hmrc.transitmovements.models.MessageData
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageStatus
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.MovementType
import uk.gov.hmrc.transitmovements.models.ObjectStoreResourceLocation
import uk.gov.hmrc.transitmovements.models.formats.PresentationFormats
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse
import uk.gov.hmrc.transitmovements.repositories.MovementsRepository
import uk.gov.hmrc.transitmovements.services.MessageService
import uk.gov.hmrc.transitmovements.services.MessagesXmlParsingService
import uk.gov.hmrc.transitmovements.services.MovementsXmlParsingService
import uk.gov.hmrc.transitmovements.services.ObjectStoreService
import uk.gov.hmrc.transitmovements.services.errors.MongoError
import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError
import uk.gov.hmrc.transitmovements.services.errors.ParseError
import uk.gov.hmrc.transitmovements.services.errors.StreamError

import java.net.URI
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

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

  private val now         = OffsetDateTime.now()
  private val nowMinusOne = now.minusMinutes(1)
  private val clock       = Clock.fixed(now.toInstant, now.getOffset)

  "getBody" - {

    "getting a small message" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementType],
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[MessageType]
    ) {
      (eori, movementType, movementId, messageId, messageType) =>
        val xml = Gen
          .stringOfN(15, Gen.alphaNumChar)
          .map(
            s => s"<test>$s</test>"
          )
          .sample
          .get
        val mockMovementsRepo = mock[MovementsRepository]
        val expectedResponse: EitherT[Future, MongoError, MessageResponse] = EitherT.rightT[Future, MongoError](
          MessageResponse(
            messageId,
            now,
            Some(messageType),
            Some(xml),
            Some(MessageStatus.Success),
            None
          )
        )
        when(
          mockMovementsRepo.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(expectedResponse)

        val mockObjectStoreService         = mock[ObjectStoreService]
        val mockMessagesXmlParsingSerivce  = mock[MessagesXmlParsingService]
        val mockMovementsXmlParsingSerivce = mock[MovementsXmlParsingService]
        val mockMessageService             = mock[MessageService]

        implicit val tfc: TemporaryFileCreator = SingletonTemporaryFileCreator

        val sut = new MessageBodyController(
          stubControllerComponents(),
          mockMovementsRepo,
          mockObjectStoreService,
          mockMessagesXmlParsingSerivce,
          mockMovementsXmlParsingSerivce,
          mockMessageService,
          clock
        )
        val result: Future[Result] = sut.getBody(eori, movementType, movementId, messageId)(FakeRequest("GET", "/"))

        status(result) mustBe OK
        contentAsString(result) mustBe xml

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
      arbitrary[MessageType]
    ) {
      (eori, movementType, movementId, messageId, messageType) =>
        val objectStoreUri = testObjectStoreURI(movementId, messageId, now)
        val xml = Gen
          .stringOfN(15, Gen.alphaNumChar)
          .map(
            s => s"<test>$s</test>"
          )
          .sample
          .get
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
              Future.successful[Either[MongoError, MessageResponse]](
                Right(
                  MessageResponse(
                    messageId,
                    now,
                    Some(messageType),
                    None,
                    Some(MessageStatus.Success),
                    Some(new URI(objectStoreUri.value))
                  )
                )
              )
            )
          )

        val mockObjectStoreService = mock[ObjectStoreService]
        when(mockObjectStoreService.getObjectStoreFile(ObjectStoreResourceLocation(eqTo(objectStoreUri.asResourceLocation.get.value)))(any(), any()))
          .thenReturn(EitherT.rightT(Source.single(ByteString(xml))))

        val mockMessagesXmlParsingSerivce  = mock[MessagesXmlParsingService]
        val mockMovementsXmlParsingSerivce = mock[MovementsXmlParsingService]
        val mockMessageService             = mock[MessageService]

        implicit val tfc: TemporaryFileCreator = SingletonTemporaryFileCreator

        val sut = new MessageBodyController(
          stubControllerComponents(),
          mockMovementsRepo,
          mockObjectStoreService,
          mockMessagesXmlParsingSerivce,
          mockMovementsXmlParsingSerivce,
          mockMessageService,
          clock
        )
        val result: Future[Result] = sut.getBody(eori, movementType, movementId, messageId)(FakeRequest("GET", "/"))

        status(result) mustBe OK
        contentAsString(result) mustBe xml

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
              MessageResponse(
                messageId,
                now,
                Some(messageType),
                None,
                Some(MessageStatus.Pending),
                None
              )
            )
          )

        val mockObjectStoreService = mock[ObjectStoreService]

        val mockMessagesXmlParsingSerivce  = mock[MessagesXmlParsingService]
        val mockMovementsXmlParsingSerivce = mock[MovementsXmlParsingService]
        val mockMessageService             = mock[MessageService]

        implicit val tfc: TemporaryFileCreator = SingletonTemporaryFileCreator

        val sut = new MessageBodyController(
          stubControllerComponents(),
          mockMovementsRepo,
          mockObjectStoreService,
          mockMessagesXmlParsingSerivce,
          mockMovementsXmlParsingSerivce,
          mockMessageService,
          clock
        )
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
          .thenReturn(EitherT.leftT[Future, MessageResponse](MongoError.DocumentNotFound("not found")))

        val mockObjectStoreService = mock[ObjectStoreService]

        val mockMessagesXmlParsingSerivce  = mock[MessagesXmlParsingService]
        val mockMovementsXmlParsingSerivce = mock[MovementsXmlParsingService]
        val mockMessageService             = mock[MessageService]

        implicit val tfc: TemporaryFileCreator = SingletonTemporaryFileCreator

        val sut = new MessageBodyController(
          stubControllerComponents(),
          mockMovementsRepo,
          mockObjectStoreService,
          mockMessagesXmlParsingSerivce,
          mockMovementsXmlParsingSerivce,
          mockMessageService,
          clock
        )
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
          .thenReturn(EitherT.leftT(MongoError.DocumentNotFound(s"Body of message ID ${messageId.value} for movement ID ${movementId.value} was not found")))

        val mockObjectStoreService = mock[ObjectStoreService]

        val mockMessagesXmlParsingSerivce  = mock[MessagesXmlParsingService]
        val mockMovementsXmlParsingSerivce = mock[MovementsXmlParsingService]
        val mockMessageService             = mock[MessageService]

        implicit val tfc: TemporaryFileCreator = SingletonTemporaryFileCreator

        val sut = new MessageBodyController(
          stubControllerComponents(),
          mockMovementsRepo,
          mockObjectStoreService,
          mockMessagesXmlParsingSerivce,
          mockMovementsXmlParsingSerivce,
          mockMessageService,
          clock
        )
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
              MessageResponse(
                messageId,
                now,
                Some(messageType),
                None,
                Some(MessageStatus.Success),
                Some(new URI(objectStoreUri.value))
              )
            )
          )

        val mockObjectStoreService = mock[ObjectStoreService]
        when(mockObjectStoreService.getObjectStoreFile(ObjectStoreResourceLocation(eqTo(objectStoreUri.asResourceLocation.get.value)))(any(), any()))
          .thenReturn(EitherT.leftT(ObjectStoreError.FileNotFound("...")))

        val mockMessagesXmlParsingSerivce  = mock[MessagesXmlParsingService]
        val mockMovementsXmlParsingSerivce = mock[MovementsXmlParsingService]
        val mockMessageService             = mock[MessageService]

        implicit val tfc: TemporaryFileCreator = SingletonTemporaryFileCreator

        val sut = new MessageBodyController(
          stubControllerComponents(),
          mockMovementsRepo,
          mockObjectStoreService,
          mockMessagesXmlParsingSerivce,
          mockMovementsXmlParsingSerivce,
          mockMessageService,
          clock
        )
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

  "createBody" - {

    "must return Created when the body has been created and is added to Mongo with no extra extracted data" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[MessageType],
      arbitrary[MovementType],
      Gen.stringOfN(15, Gen.alphaNumChar)
    ) {
      (eori, movementId, messageId, messageType, movementType, string) =>
        val ControllerAndMocks(sut, movementsRepository, _, messagesXmlParsingService, movementsXmlParsingService, messageService) = createController()
        val extractDataEither: EitherT[Future, ParseError, Option[ExtractedData]] = {
          if (messageType == MessageType.DeclarationData)
            EitherT.rightT(Some(DeclarationData(Some(eori), OffsetDateTime.now(clock), LocalReferenceNumber(string))))
          else EitherT.rightT(None)
        }
        val lrnOption: Option[LocalReferenceNumber] = {
          if (messageType == MessageType.DeclarationData) Some(LocalReferenceNumber(string))
          else None
        }

        val eoriOption: Option[EORINumber] = {
          if (messageType == MessageType.DeclarationData) Some(eori)
          else None
        }

        when(
          movementsRepository.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(
            EitherT.rightT(
              MessageResponse(
                messageId,
                now,
                None,
                None,
                Some(MessageStatus.Pending),
                None
              )
            )
          )

        when(messagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(messageType)))
          .thenReturn(EitherT.rightT(MessageData(now, None)))

        when(movementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, _]]))
          .thenReturn(extractDataEither)

        when(movementsRepository.restrictDuplicateLRN(LocalReferenceNumber(string)))
          .thenReturn(EitherT.rightT((): Unit))

        when(
          messageService
            .storeIfLarge(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), any[Long], any[Source[ByteString, _]])(any[HeaderCarrier])
        ).thenReturn(EitherT.rightT(BodyStorage.mongo(string)))

        when(
          movementsRepository.updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            argThat(UpdateMessageDataMatcher(None, Some(string), messageType.statusOnAttach, Some(messageType), Some(now))),
            eqTo(now)
          )
        ).thenReturn(EitherT.rightT((): Unit))

        when(
          movementsRepository.updateMovement(
            MovementId(eqTo(movementId.value)),
            eqTo(eoriOption),
            eqTo(None),
            eqTo(lrnOption),
            eqTo(now)
          )
        )
          .thenReturn(EitherT.rightT((): Unit))

        val request                = FakeRequest("POST", "/", FakeHeaders(Seq("x-message-type" -> messageType.code)), Source.single(ByteString(string)))
        val result: Future[Result] = sut.createBody(eori, movementType, movementId, messageId)(request)

        status(result) mustBe CREATED
    }

    "must return Created when the body has been created and is added to Mongo with extra extracted arrival data" in forAll(
      arbitrary[EORINumber],
      arbitrary[EORINumber],
      arbitrary[MovementReferenceNumber],
      arbitrary[MovementId],
      arbitrary[MessageId],
      Gen.stringOfN(15, Gen.alphaNumChar)
    ) {
      (eori, movementEori, movementReferenceNumber, movementId, messageId, string) =>
        val messageType  = MessageType.ArrivalNotification
        val movementType = MovementType.Arrival

        val ControllerAndMocks(sut, movementsRepository, _, messagesXmlParsingService, movementsXmlParsingService, messageService) = createController()

        when(
          movementsRepository.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(
            EitherT.rightT(
              MessageResponse(
                messageId,
                now,
                None,
                None,
                Some(MessageStatus.Pending),
                None
              )
            )
          )

        when(messagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(messageType)))
          .thenReturn(EitherT.rightT(MessageData(now, None)))

        when(movementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, _]]))
          .thenReturn(EitherT.rightT(Some(ArrivalData(Some(movementEori), nowMinusOne, movementReferenceNumber))))

        when(
          messageService
            .storeIfLarge(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), any[Long], any[Source[ByteString, _]])(any[HeaderCarrier])
        ).thenReturn(EitherT.rightT(BodyStorage.mongo(string)))

        when(
          movementsRepository.updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            argThat(UpdateMessageDataMatcher(None, Some(string), messageType.statusOnAttach, Some(messageType), Some(now))),
            eqTo(now)
          )
        ).thenReturn(EitherT.rightT((): Unit))

        when(
          movementsRepository.updateMovement(
            MovementId(eqTo(movementId.value)),
            eqTo(Some(movementEori)),
            eqTo(Some(movementReferenceNumber)),
            eqTo(None),
            eqTo(now)
          )
        )
          .thenReturn(EitherT.rightT((): Unit))

        val request                = FakeRequest("POST", "/", FakeHeaders(Seq("x-message-type" -> messageType.code)), Source.single(ByteString(string)))
        val result: Future[Result] = sut.createBody(eori, movementType, movementId, messageId)(request)

        status(result) mustBe CREATED
    }

    "must return Created when the body has been created and is added to Mongo with extra extracted departure data" in forAll(
      arbitrary[EORINumber],
      arbitrary[EORINumber],
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitraryLRN.arbitrary,
      Gen.stringOfN(15, Gen.alphaNumChar)
    ) {
      (eori, movementEori, movementId, messageId, lrn, string) =>
        val messageType  = MessageType.DeclarationData
        val movementType = MovementType.Departure

        val ControllerAndMocks(sut, movementsRepository, _, messagesXmlParsingService, movementsXmlParsingService, messageService) = createController()

        when(
          movementsRepository.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(
            EitherT.rightT(
              MessageResponse(
                messageId,
                now,
                None,
                None,
                Some(MessageStatus.Pending),
                None
              )
            )
          )

        when(messagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(messageType)))
          .thenReturn(EitherT.rightT(MessageData(now, None)))

        when(movementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, _]]))
          .thenReturn(
            EitherT.rightT(
              Some(DeclarationData(Some(movementEori), now, lrn))
            )
          )

        when(movementsRepository.restrictDuplicateLRN(lrn))
          .thenReturn(EitherT.rightT((): Unit))

        when(
          messageService
            .storeIfLarge(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), any[Long], any[Source[ByteString, _]])(any[HeaderCarrier])
        ).thenReturn(EitherT.rightT(BodyStorage.mongo(string)))

        when(
          movementsRepository.updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            argThat(UpdateMessageDataMatcher(None, Some(string), messageType.statusOnAttach, Some(messageType), Some(now))),
            eqTo(now)
          )
        ).thenReturn(EitherT.rightT((): Unit))

        when(
          movementsRepository.updateMovement(
            MovementId(eqTo(movementId.value)),
            eqTo(Some(movementEori)),
            eqTo(None),
            eqTo(Some(lrn)),
            eqTo(now)
          )
        )
          .thenReturn(EitherT.rightT((): Unit))

        val request                = FakeRequest("POST", "/", FakeHeaders(Seq("x-message-type" -> messageType.code)), Source.single(ByteString(string)))
        val result: Future[Result] = sut.createBody(eori, movementType, movementId, messageId)(request)

        status(result) mustBe CREATED
    }

    "must return Created when the body has been created and is added to Mongo with extra message data" in forAll(
      arbitrary[EORINumber],
      arbitrary[EORINumber],
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitraryLRN.arbitrary,
      Gen.stringOfN(15, Gen.alphaNumChar)
    ) {
      (eori, movementEori, movementId, messageId, lrn, string) =>
        val messageType  = MessageType.DeclarationData
        val movementType = MovementType.Departure

        val ControllerAndMocks(sut, movementsRepository, _, messagesXmlParsingService, movementsXmlParsingService, messageService) = createController()

        when(
          movementsRepository.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(
            EitherT.rightT(
              MessageResponse(
                messageId,
                now,
                None,
                None,
                Some(MessageStatus.Pending),
                None
              )
            )
          )

        when(messagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(messageType)))
          .thenReturn(EitherT.rightT(MessageData(now, None)))

        when(movementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, _]]))
          .thenReturn(
            EitherT.rightT(
              Some(DeclarationData(Some(movementEori), now, lrn))
            )
          )

        when(movementsRepository.restrictDuplicateLRN(lrn))
          .thenReturn(EitherT.rightT((): Unit))

        when(
          messageService
            .storeIfLarge(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), any[Long], any[Source[ByteString, _]])(any[HeaderCarrier])
        ).thenReturn(EitherT.rightT(BodyStorage.mongo(string)))

        when(
          movementsRepository.updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            argThat(UpdateMessageDataMatcher(None, Some(string), messageType.statusOnAttach, Some(messageType), Some(now))),
            eqTo(now)
          )
        ).thenReturn(EitherT.rightT((): Unit))

        when(
          movementsRepository.updateMovement(
            MovementId(eqTo(movementId.value)),
            eqTo(Some(movementEori)),
            eqTo(None),
            eqTo(Some(lrn)),
            eqTo(now)
          )
        )
          .thenReturn(EitherT.rightT((): Unit))

        val request                = FakeRequest("POST", "/", FakeHeaders(Seq("x-message-type" -> messageType.code)), Source.single(ByteString(string)))
        val result: Future[Result] = sut.createBody(eori, movementType, movementId, messageId)(request)

        status(result) mustBe CREATED
    }

    "must return Conflict when duplicate entry of LRN" in forAll(
      arbitrary[EORINumber],
      arbitrary[EORINumber],
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitraryLRN.arbitrary,
      Gen.stringOfN(15, Gen.alphaNumChar)
    ) {
      (eori, movementEori, movementId, messageId, lrn, string) =>
        val messageType  = MessageType.DeclarationData
        val movementType = MovementType.Departure

        val ControllerAndMocks(sut, movementsRepository, _, messagesXmlParsingService, movementsXmlParsingService, messageService) = createController()

        when(
          movementsRepository.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(
            EitherT.rightT(
              MessageResponse(
                messageId,
                now,
                None,
                None,
                Some(MessageStatus.Pending),
                None
              )
            )
          )

        when(messagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(messageType)))
          .thenReturn(EitherT.rightT(MessageData(now, None)))

        when(movementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, _]]))
          .thenReturn(
            EitherT.rightT(
              Some(DeclarationData(Some(movementEori), now, lrn))
            )
          )

        when(movementsRepository.restrictDuplicateLRN(lrn))
          .thenReturn(EitherT.leftT(MongoError.ConflictError("LRN has previously been used and cannot be reused", lrn)))

        when(
          messageService
            .storeIfLarge(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), any[Long], any[Source[ByteString, _]])(any[HeaderCarrier])
        ).thenReturn(EitherT.rightT(BodyStorage.mongo(string)))

        when(
          movementsRepository.updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            argThat(UpdateMessageDataMatcher(None, Some(string), messageType.statusOnAttach, Some(messageType), Some(now))),
            eqTo(now)
          )
        ).thenReturn(EitherT.rightT((): Unit))

        when(
          movementsRepository.updateMovement(
            MovementId(eqTo(movementId.value)),
            eqTo(Some(movementEori)),
            eqTo(None),
            eqTo(Some(lrn)),
            eqTo(now)
          )
        )
          .thenReturn(EitherT.rightT((): Unit))

        val request                = FakeRequest("POST", "/", FakeHeaders(Seq("x-message-type" -> messageType.code)), Source.single(ByteString(string)))
        val result: Future[Result] = sut.createBody(eori, movementType, movementId, messageId)(request)

        status(result) mustBe CONFLICT
    }

    "must return Not Found if the message doesn't exist" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementId],
      arbitrary[MessageId],
      Gen.stringOfN(15, Gen.alphaNumChar)
    ) {
      (eori, movementId, messageId, string) =>
        val messageType  = MessageType.DeclarationData
        val movementType = MovementType.Departure

        val ControllerAndMocks(sut, movementsRepository, _, _, _, _) = createController()

        when(
          movementsRepository.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(EitherT.leftT(MongoError.DocumentNotFound("test")))

        val request                = FakeRequest("POST", "/", FakeHeaders(Seq("x-message-type" -> messageType.code)), Source.single(ByteString(string)))
        val result: Future[Result] = sut.createBody(eori, movementType, movementId, messageId)(request)

        status(result) mustBe NOT_FOUND
    }

    "must return Not Found if the movement doesn't exist" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementId],
      arbitrary[MessageId],
      Gen.stringOfN(15, Gen.alphaNumChar)
    ) {
      (eori, movementId, messageId, string) =>
        val messageType  = MessageType.DeclarationData
        val movementType = MovementType.Departure

        val ControllerAndMocks(sut, movementsRepository, _, _, _, _) = createController()

        when(
          movementsRepository.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(EitherT.leftT(MongoError.DocumentNotFound("test")))

        val request                = FakeRequest("POST", "/", FakeHeaders(Seq("x-message-type" -> messageType.code)), Source.single(ByteString(string)))
        val result: Future[Result] = sut.createBody(eori, movementType, movementId, messageId)(request)

        status(result) mustBe NOT_FOUND
    }

    "must return Conflict when the body already exists" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementId],
      arbitrary[MessageId],
      Gen.stringOfN(15, Gen.alphaNumChar)
    ) {
      (eori, movementId, messageId, string) =>
        val messageType  = MessageType.DeclarationData
        val movementType = MovementType.Departure

        val ControllerAndMocks(sut, movementsRepository, _, _, _, _) = createController()

        when(
          movementsRepository.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(
            EitherT.rightT(
              MessageResponse(
                messageId,
                now,
                None,
                Some(string),
                Some(MessageStatus.Processing),
                None
              )
            )
          )

        val request                = FakeRequest("POST", "/", FakeHeaders(Seq("x-message-type" -> messageType.code)), Source.single(ByteString(string)))
        val result: Future[Result] = sut.createBody(eori, movementType, movementId, messageId)(request)

        status(result) mustBe CONFLICT
    }

    "must return Bad Request if it cannot be parsed by the message data parser" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementId],
      arbitrary[MessageId],
      Gen.stringOfN(15, Gen.alphaNumChar),
      Gen.oneOf(ParseError.NoElementFound("a"), ParseError.TooManyElementsFound("a"), ParseError.BadDateTime("a", new DateTimeParseException("a", "a", 0)))
    ) {
      (eori, movementId, messageId, string, ex) =>
        val messageType  = MessageType.DeclarationData
        val movementType = MovementType.Departure

        val ControllerAndMocks(sut, movementsRepository, _, messagesXmlParsingService, _, _) = createController()

        when(
          movementsRepository.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(
            EitherT.rightT(
              MessageResponse(
                messageId,
                now,
                None,
                None,
                Some(MessageStatus.Pending),
                None
              )
            )
          )

        when(messagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(messageType)))
          .thenReturn(EitherT.leftT(ex))

        val request                = FakeRequest("POST", "/", FakeHeaders(Seq("x-message-type" -> messageType.code)), Source.single(ByteString(string)))
        val result: Future[Result] = sut.createBody(eori, movementType, movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST
    }

    "must return Internal Server Error if it throws an error during parsing by the message data parser" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementId],
      arbitrary[MessageId],
      Gen.stringOfN(15, Gen.alphaNumChar)
    ) {
      (eori, movementId, messageId, string) =>
        val messageType  = MessageType.DeclarationData
        val movementType = MovementType.Departure

        val ControllerAndMocks(sut, movementsRepository, _, messagesXmlParsingService, _, _) = createController()

        when(
          movementsRepository.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(
            EitherT.rightT(
              MessageResponse(
                messageId,
                now,
                None,
                None,
                Some(MessageStatus.Pending),
                None
              )
            )
          )

        when(messagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(messageType)))
          .thenReturn(EitherT.leftT(ParseError.UnexpectedError(None)))

        val request                = FakeRequest("POST", "/", FakeHeaders(Seq("x-message-type" -> messageType.code)), Source.single(ByteString(string)))
        val result: Future[Result] = sut.createBody(eori, movementType, movementId, messageId)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
    }

    "must return Bad Request if it cannot be parsed by the movement data parser" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementId],
      arbitrary[MessageId],
      Gen.stringOfN(15, Gen.alphaNumChar),
      Gen.oneOf(ParseError.NoElementFound("a"), ParseError.TooManyElementsFound("a"), ParseError.BadDateTime("a", new DateTimeParseException("a", "a", 0)))
    ) {
      (eori, movementId, messageId, string, ex) =>
        val messageType  = MessageType.DeclarationData
        val movementType = MovementType.Departure

        val ControllerAndMocks(sut, movementsRepository, _, messagesXmlParsingService, movementsXmlParsingService, _) = createController()

        when(
          movementsRepository.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(
            EitherT.rightT(
              MessageResponse(
                messageId,
                now,
                None,
                None,
                Some(MessageStatus.Pending),
                None
              )
            )
          )

        when(messagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(messageType)))
          .thenReturn(EitherT.rightT(MessageData(now, None)))

        when(movementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, _]]))
          .thenReturn(EitherT.leftT(ex))

        val request                = FakeRequest("POST", "/", FakeHeaders(Seq("x-message-type" -> messageType.code)), Source.single(ByteString(string)))
        val result: Future[Result] = sut.createBody(eori, movementType, movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST
    }

    "must return Internal Server Error if it throws an error during parsing by the movement data parser" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementId],
      arbitrary[MessageId],
      Gen.stringOfN(15, Gen.alphaNumChar)
    ) {
      (eori, movementId, messageId, string) =>
        val messageType  = MessageType.DeclarationData
        val movementType = MovementType.Departure

        val ControllerAndMocks(sut, movementsRepository, _, messagesXmlParsingService, movementsXmlParsingService, _) = createController()

        when(
          movementsRepository.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(
            EitherT.rightT(
              MessageResponse(
                messageId,
                now,
                None,
                None,
                Some(MessageStatus.Pending),
                None
              )
            )
          )

        when(messagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(messageType)))
          .thenReturn(EitherT.rightT(MessageData(now, None)))

        when(movementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, _]]))
          .thenReturn(EitherT.leftT(ParseError.UnexpectedError(None)))

        val request                = FakeRequest("POST", "/", FakeHeaders(Seq("x-message-type" -> messageType.code)), Source.single(ByteString(string)))
        val result: Future[Result] = sut.createBody(eori, movementType, movementId, messageId)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
    }

    "must return Internal Server Error if the object cannot be prepared for storage" in forAll(
      arbitrary[EORINumber],
      arbitrary[EORINumber],
      arbitrary[MovementReferenceNumber],
      arbitrary[MovementId],
      arbitrary[MessageId],
      Gen.stringOfN(15, Gen.alphaNumChar)
    ) {
      (eori, movementEori, movementReferenceNumber, movementId, messageId, string) =>
        val messageType  = MessageType.DeclarationData
        val movementType = MovementType.Departure

        val ControllerAndMocks(sut, movementsRepository, _, messagesXmlParsingService, movementsXmlParsingService, messageService) = createController()

        when(
          movementsRepository.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(
            EitherT.rightT(
              MessageResponse(
                messageId,
                now,
                None,
                None,
                Some(MessageStatus.Pending),
                None
              )
            )
          )

        when(messagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(messageType)))
          .thenReturn(EitherT.rightT(MessageData(now, Some(movementReferenceNumber))))

        when(movementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, _]]))
          .thenReturn(
            EitherT.rightT(
              Some(DeclarationData(Some(movementEori), nowMinusOne, LocalReferenceNumber(string)))
            )
          )

        when(
          messageService
            .storeIfLarge(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), any[Long], any[Source[ByteString, _]])(any[HeaderCarrier])
        ).thenReturn(EitherT.leftT(StreamError.UnexpectedError(None)))

        val request                = FakeRequest("POST", "/", FakeHeaders(Seq("x-message-type" -> messageType.code)), Source.single(ByteString(string)))
        val result: Future[Result] = sut.createBody(eori, movementType, movementId, messageId)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
    }

    "must return Internal Server Error when Mongo is unable to store a message" in forAll(
      arbitrary[EORINumber],
      arbitrary[EORINumber],
      arbitrary[MovementReferenceNumber],
      arbitrary[MovementId],
      arbitrary[MessageId],
      Gen.stringOfN(15, Gen.alphaNumChar)
    ) {
      (eori, movementEori, movementReferenceNumber, movementId, messageId, string) =>
        val messageType  = MessageType.DeclarationData
        val movementType = MovementType.Departure

        val ControllerAndMocks(sut, movementsRepository, _, messagesXmlParsingService, movementsXmlParsingService, messageService) = createController()

        when(
          movementsRepository.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(
            EitherT.rightT(
              MessageResponse(
                messageId,
                now,
                None,
                None,
                Some(MessageStatus.Pending),
                None
              )
            )
          )

        when(messagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(messageType)))
          .thenReturn(EitherT.rightT(MessageData(now, Some(movementReferenceNumber))))

        when(movementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, _]]))
          .thenReturn(
            EitherT.rightT(
              Some(DeclarationData(Some(movementEori), nowMinusOne, LocalReferenceNumber(string)))
            )
          )

        when(
          messageService
            .storeIfLarge(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), any[Long], any[Source[ByteString, _]])(any[HeaderCarrier])
        ).thenReturn(EitherT.rightT(BodyStorage.mongo(string)))

        when(
          movementsRepository.updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            argThat(UpdateMessageDataMatcher(None, Some(string), messageType.statusOnAttach, Some(messageType), Some(now))),
            eqTo(now)
          )
        ).thenReturn(EitherT.leftT(MongoError.UnexpectedError(None)))

        when(
          movementsRepository.updateMovement(
            MovementId(eqTo(movementId.value)),
            eqTo(Some(movementEori)),
            eqTo(Some(movementReferenceNumber)),
            eqTo(None),
            eqTo(now)
          )
        )
          .thenReturn(EitherT.rightT((): Unit))

        val request                = FakeRequest("POST", "/", FakeHeaders(Seq("x-message-type" -> messageType.code)), Source.single(ByteString(string)))
        val result: Future[Result] = sut.createBody(eori, movementType, movementId, messageId)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
    }

    "must return Internal Server Error when Mongo is unable to update a movement" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[MessageType],
      arbitrary[MovementType],
      Gen.stringOfN(15, Gen.alphaNumChar)
    ) {
      (eori, movementId, messageId, messageType, movementType, string) =>
        val ControllerAndMocks(sut, movementsRepository, _, messagesXmlParsingService, movementsXmlParsingService, messageService) = createController()

        when(
          movementsRepository.getSingleMessage(
            EORINumber(eqTo(eori.value)),
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(movementType)
          )
        )
          .thenReturn(
            EitherT.rightT(
              MessageResponse(
                messageId,
                now,
                None,
                None,
                Some(MessageStatus.Pending),
                None
              )
            )
          )

        when(messagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(messageType)))
          .thenReturn(EitherT.rightT(MessageData(now, None)))

        when(movementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, _]]))
          .thenReturn(EitherT.rightT(None))

        when(
          messageService
            .storeIfLarge(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), any[Long], any[Source[ByteString, _]])(any[HeaderCarrier])
        ).thenReturn(EitherT.rightT(BodyStorage.mongo(string)))

        when(
          movementsRepository.updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            argThat(UpdateMessageDataMatcher(None, Some(string), messageType.statusOnAttach, Some(messageType), Some(now))),
            eqTo(now)
          )
        ).thenReturn(EitherT.rightT((): Unit))

        when(movementsRepository.updateMovement(MovementId(eqTo(movementId.value)), eqTo(None), eqTo(None), eqTo(None), eqTo(nowMinusOne)))
          .thenReturn(EitherT.leftT(MongoError.UpdateNotAcknowledged("bleh")))

        val request                = FakeRequest("POST", "/", FakeHeaders(Seq("x-message-type" -> messageType.code)), Source.single(ByteString(string)))
        val result: Future[Result] = sut.createBody(eori, movementType, movementId, messageId)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
    }

    def createController(): ControllerAndMocks = {
      implicit val tfc: TemporaryFileCreator = SingletonTemporaryFileCreator

      val mockRepository: MovementsRepository                        = mock[MovementsRepository]
      val mockObjectStoreService: ObjectStoreService                 = mock[ObjectStoreService]
      val mockMessagesXmlParsingService: MessagesXmlParsingService   = mock[MessagesXmlParsingService]
      val mockMovementsXmlParsingService: MovementsXmlParsingService = mock[MovementsXmlParsingService]
      val mockMessageService: MessageService                         = mock[MessageService]

      val controller = new MessageBodyController(
        stubControllerComponents(),
        mockRepository,
        mockObjectStoreService,
        mockMessagesXmlParsingService,
        mockMovementsXmlParsingService,
        mockMessageService,
        clock
      )

      ControllerAndMocks(controller, mockRepository, mockObjectStoreService, mockMessagesXmlParsingService, mockMovementsXmlParsingService, mockMessageService)
    }

    case class ControllerAndMocks(
      controller: MessageBodyController,
      mockRepository: MovementsRepository,
      mockObjectStoreService: ObjectStoreService,
      mockMessagesXmlParsingService: MessagesXmlParsingService,
      mockMovementsXmlParsingService: MovementsXmlParsingService,
      mockMessageService: MessageService
    )

  }

}
