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
import akka.util.Timeout
import cats.data.EitherT
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchersSugar.eqTo
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoSugar.reset
import org.mockito.MockitoSugar.when
import org.mockito.MockitoSugar.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterEach
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
import play.api.libs.Files.SingletonTemporaryFileCreator
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.Json
import play.api.mvc.AnyContentAsEmpty
import play.api.mvc.Request
import play.api.mvc.Result
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers.await
import play.api.test.Helpers.contentAsJson
import play.api.test.Helpers.status
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpVerbs.POST
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.transitmovements.base.SpecBase
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.config.AppConfig
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.matchers.UpdateMessageDataMatcher
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models._
import uk.gov.hmrc.transitmovements.models.formats.PresentationFormats
import uk.gov.hmrc.transitmovements.models.requests.UpdateMessageMetadata
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse
import uk.gov.hmrc.transitmovements.repositories.MovementsRepository
import uk.gov.hmrc.transitmovements.services._
import uk.gov.hmrc.transitmovements.services.errors.MongoError
import uk.gov.hmrc.transitmovements.services.errors.MongoError.UnexpectedError
import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError
import uk.gov.hmrc.transitmovements.services.errors.ParseError
import uk.gov.hmrc.transitmovements.services.errors.StreamError

import java.net.URI
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID.randomUUID
import scala.annotation.nowarn
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal
import scala.xml.NodeSeq

// this warning is a false one due to how the mock matchers work
@nowarn("msg=dead code following this construct")
class MovementsControllerSpec
    extends SpecBase
    with TestActorSystem
    with Matchers
    with OptionValues
    with ScalaFutures
    with BeforeAndAfterEach
    with PresentationFormats
    with ModelGenerators
    with ScalaCheckDrivenPropertyChecks {

  implicit val timeout: Timeout = 5.seconds

  // TODO: Eliminate a lot of these "global" constants in favour of test scoped constants
  val mrn: MovementReferenceNumber                      = arbitraryMovementReferenceNumber.arbitrary.sample.get
  lazy val movementId: MovementId                       = arbitraryMovementId.arbitrary.sample.get
  lazy val messageId: MessageId                         = arbitraryMessageId.arbitrary.sample.get
  lazy val triggerId: MessageId                         = arbitraryMessageId.arbitrary.sample.get
  lazy val eoriNumber: EORINumber                       = arbitrary[EORINumber].sample.get
  lazy val objectStoreURI: URI                          = arbitrary[URI].sample.get
  lazy val updateMessageMetadata: UpdateMessageMetadata = arbitraryUpdateMessageMetadata.arbitrary.sample.get
  lazy val objectSummary: ObjectSummaryWithMd5          = arbitraryObjectSummaryWithMd5.arbitrary.sample.get

  lazy val filePath =
    Path.Directory(s"common-transit-convention-traders/movements/${arbitraryMovementId.arbitrary.sample.get}").file(randomUUID.toString).asUri

  lazy val movement: Movement = arbitrary[Movement].sample.value.copy(
    _id = movementId,
    enrollmentEORINumber = eoriNumber,
    movementEORINumber = Some(eoriNumber),
    created = now,
    updated = now,
    messages = Vector(message)
  )

  val now: OffsetDateTime           = OffsetDateTime.now
  val generatedTime: OffsetDateTime = now.minusMinutes(1)

  lazy val message: Message =
    arbitraryMessage.arbitrary.sample.get.copy(
      id = messageId,
      generated = Some(now),
      received = now,
      triggerId = Some(triggerId),
      uri = Some(new URI("http://www.google.com"))
    )

  lazy val messageFactoryEither: EitherT[Future, StreamError, Message] =
    EitherT.rightT(message)

  def fakeRequest[A](
    method: String,
    body: A,
    messageType: Option[String],
    headers: FakeHeaders = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML))
  ): Request[A] =
    FakeRequest(
      method = method,
      uri = routes.MovementsController.updateMovement(movementId, Some(triggerId)).url,
      headers = if (messageType.isDefined) headers.add("X-Message-Type" -> messageType.get) else headers,
      body = body
    )

  val mockMessagesXmlParsingService: MessagesXmlParsingService   = mock[MessagesXmlParsingService]
  val mockMovementsXmlParsingService: MovementsXmlParsingService = mock[MovementsXmlParsingService]
  val mockRepository: MovementsRepository                        = mock[MovementsRepository]
  val mockMessageFactory: MessageService                         = mock[MessageService]
  val mockMovementFactory: MovementFactory                       = mock[MovementFactory]

  val mockObjectStoreService: ObjectStoreService              = mock[ObjectStoreService]
  implicit val mockTemporaryFileCreator: TemporaryFileCreator = mock[TemporaryFileCreator]
  val mockAppConfig: AppConfig                                = mock[AppConfig]

  override def beforeEach(): Unit = {
    // TODO: Move this to specific tests as required
    when(mockMessageFactory.generateId()).thenReturn(messageId)
    when(mockMovementFactory.generateId()).thenReturn(movementId)
    super.beforeEach()
  }

  override def afterEach(): Unit = {
    reset(mockTemporaryFileCreator)
    reset(mockMovementsXmlParsingService)
    reset(mockMessagesXmlParsingService)
    reset(mockRepository)
    reset(mockMessageFactory)
    reset(mockObjectStoreService)
    super.afterEach()
  }

  val instant: OffsetDateTime = OffsetDateTime.of(2022, 8, 26, 9, 0, 0, 0, ZoneOffset.UTC)
  implicit val clock: Clock   = Clock.fixed(instant.toInstant, ZoneOffset.UTC)

  val controller =
    new MovementsController(
      stubControllerComponents(),
      mockMessageFactory,
      mockMovementFactory,
      mockRepository,
      mockMovementsXmlParsingService,
      mockMessagesXmlParsingService,
      mockObjectStoreService
    )

  "createMovement - Departure" - {

    val validXml: NodeSeq =
      <CC015C>
        <messageSender>ABC123</messageSender>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
      </CC015C>

    val validXmlStream: Source[ByteString, _] = Source.single(ByteString(validXml.mkString))

    lazy val declarationData = DeclarationData(Some(eoriNumber), OffsetDateTime.now(ZoneId.of("UTC")))

    lazy val departureDataEither: EitherT[Future, ParseError, DeclarationData] =
      EitherT.rightT(declarationData)

    lazy val messageFactoryEither: EitherT[Future, StreamError, Message] =
      EitherT.rightT(message)

    lazy val received = OffsetDateTime.now(ZoneId.of("UTC"))

    lazy val uri = new URI("test")

    lazy val size = Gen.chooseNum[Long](1L, 25000L).sample.value

    lazy val message =
      Message(
        messageId,
        received,
        Some(received),
        Some(MessageType.DeclarationData),
        Some(messageId),
        Some(uri),
        Some("content"),
        Some(size),
        Some(MessageStatus.Processing)
      )

    "must return OK if XML data extraction is successful" in {

      val tempFile = SingletonTemporaryFileCreator.create()
      when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

      when(mockMovementsXmlParsingService.extractDeclarationData(any[Source[ByteString, _]]))
        .thenReturn(departureDataEither)

      when(
        mockMovementFactory.createDeparture(
          any[String].asInstanceOf[MovementId],
          any[String].asInstanceOf[EORINumber],
          any[String].asInstanceOf[MovementType],
          any[DeclarationData],
          any[Message],
          any[OffsetDateTime],
          any[OffsetDateTime]
        )
      )
        .thenReturn(movement)

      when(
        mockMessageFactory.create(
          MovementId(eqTo(movementId.value)),
          eqTo(MessageType.DeclarationData),
          any[OffsetDateTime],
          any[OffsetDateTime],
          any[Option[MessageId]],
          any[Long],
          any[Source[ByteString, _]],
          eqTo(MessageStatus.Processing)
        )(any[HeaderCarrier])
      )
        .thenReturn(messageFactoryEither)

      when(mockRepository.insert(any()))
        .thenReturn(EitherT.rightT(Right(())))

      val request: Request[Source[ByteString, _]] = fakeRequest[Source[ByteString, _]](POST, validXmlStream, Some(MessageType.DeclarationData.code))

      val result: Future[Result] =
        controller.createMovement(eoriNumber, MovementType.Departure)(request)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj(
        "movementId" -> movementId.value,
        "messageId"  -> messageId.value
      )

      verify(mockMessageFactory, times(1)).create(
        MovementId(eqTo(movementId.value)),
        eqTo(MessageType.DeclarationData),
        any[OffsetDateTime],
        any[OffsetDateTime],
        any[Option[MessageId]],
        any[Long],
        any[Source[ByteString, _]],
        eqTo(MessageStatus.Processing)
      )(any[HeaderCarrier])
    }

    "must return BAD_REQUEST when XML data extraction fails" - {

      "contains message to indicate element not found" in {

        val elementNotFoundXml: NodeSeq =
          <CC015C></CC015C>

        when(mockMovementsXmlParsingService.extractDeclarationData(any[Source[ByteString, _]]))
          .thenReturn(EitherT.leftT(ParseError.NoElementFound("messageSender")))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = fakeRequest(POST, elementNotFoundXml, Some(MessageType.DeclarationData.code))

        val result =
          controller.createMovement(eoriNumber, MovementType.Departure)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Element messageSender not found"
        )
      }

      "contains message to indicate too many elements found" in {

        val tooManyFoundXml: NodeSeq =
          <CC015C>
            <messageSender>GB1234</messageSender>
            <messageSender>XI1234</messageSender>
          </CC015C>

        when(mockMovementsXmlParsingService.extractDeclarationData(any[Source[ByteString, _]]))
          .thenReturn(EitherT.leftT(ParseError.TooManyElementsFound("messageSender")))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = fakeRequest(POST, tooManyFoundXml, Some(MessageType.DeclarationData.code))

        val result =
          controller.createMovement(eoriNumber, MovementType.Departure)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Found too many elements of type messageSender"
        )
      }

      "contains message to indicate date time failure" in {

        val tooManyFoundXml: NodeSeq =
          <CC015C>
            <messageSender>GB1234</messageSender>
            <preparationDateAndTime>no</preparationDateAndTime>
          </CC015C>

        val cs: CharSequence = "no"

        when(mockMovementsXmlParsingService.extractDeclarationData(any[Source[ByteString, _]]))
          .thenReturn(
            EitherT.leftT(
              ParseError.BadDateTime("preparationDateAndTime", new DateTimeParseException("Text 'no' could not be parsed at index 0", cs, 0))
            )
          )

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = fakeRequest(POST, tooManyFoundXml, Some(MessageType.DeclarationData.code))

        val result =
          controller.createMovement(eoriNumber, MovementType.Departure)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Could not parse datetime for preparationDateAndTime: Text 'no' could not be parsed at index 0"
        )
      }
    }

    "must return INTERNAL_SERVICE_ERROR" - {

      "when an invalid xml causes an unknown ParseError to be thrown" in {

        val unknownErrorXml: String =
          "<CC015C><messageSender>GB1234</messageSender>"

        when(mockMovementsXmlParsingService.extractDeclarationData(any[Source[ByteString, _]]))
          .thenReturn(EitherT.leftT(ParseError.UnexpectedError(Some(new IllegalArgumentException()))))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = FakeRequest(
          method = POST,
          uri = routes.MovementsController.createMovement(eoriNumber, MovementType.Departure).url,
          headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML)),
          body = unknownErrorXml
        )

        val result =
          controller.createMovement(eoriNumber, MovementType.Departure)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )
      }

      "when file creation fails" in {

        when(mockTemporaryFileCreator.create()).thenThrow(new Exception("File creation failed"))

        val request = fakeRequest(POST, validXml, Some(MessageType.DeclarationData.code))

        val result =
          controller.createMovement(eoriNumber, MovementType.Departure)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )
      }
    }
  }

  "createMovement - Arrival" - {

    val validXml: NodeSeq =
      <CC007C>
        <messageSender>ABC123</messageSender>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
        <TraderAtDestination>
          <identificationNumber>eori</identificationNumber>
        </TraderAtDestination>
        <TransitOperation>
          <MRN>movement reerence number</MRN>
        </TransitOperation>
      </CC007C>

    val validXmlStream = Source.single(ByteString(validXml.mkString))

    lazy val arrivalData = ArrivalData(Some(eoriNumber), OffsetDateTime.now(ZoneId.of("UTC")), mrn)

    lazy val arrivalDataEither: EitherT[Future, ParseError, ArrivalData] =
      EitherT.rightT(arrivalData)

    lazy val objectSummaryEither: EitherT[Future, ObjectStoreError, ObjectSummaryWithMd5] = EitherT.rightT(objectSummary)

    lazy val received = OffsetDateTime.now(ZoneId.of("UTC"))

    lazy val uri = new URI("test")

    lazy val size = Gen.chooseNum(1L, 25000L).sample.value

    "must return OK if XML data extraction is successful" in {

      val tempFile = SingletonTemporaryFileCreator.create()
      when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

      when(mockMovementsXmlParsingService.extractArrivalData(any[Source[ByteString, _]]))
        .thenReturn(arrivalDataEither)

      when(
        mockMovementFactory.createArrival(
          any[String].asInstanceOf[MovementId],
          any[String].asInstanceOf[EORINumber],
          any[String].asInstanceOf[MovementType],
          any[ArrivalData],
          any[Message],
          any[OffsetDateTime],
          any[OffsetDateTime]
        )
      )
        .thenReturn(movement)

      when(
        mockMessageFactory.create(
          MovementId(eqTo(movementId.value)),
          eqTo(MessageType.ArrivalNotification),
          any[OffsetDateTime],
          any[OffsetDateTime],
          any[Option[MessageId]],
          any[Long],
          any[Source[ByteString, _]],
          eqTo(MessageStatus.Processing)
        )(any[HeaderCarrier])
      )
        .thenReturn(messageFactoryEither)

      when(mockRepository.insert(any()))
        .thenReturn(EitherT.rightT(Right(())))

      val request = fakeRequest[Source[ByteString, _]](POST, validXmlStream, Some(MessageType.ArrivalNotification.code))

      val result: Future[Result] =
        controller.createMovement(eoriNumber, MovementType.Arrival)(request)

      await(result)
      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj(
        "movementId" -> movementId.value,
        "messageId"  -> messageId.value
      )

      verify(mockMessageFactory, times(1)).create(
        MovementId(eqTo(movementId.value)),
        eqTo(MessageType.ArrivalNotification),
        any[OffsetDateTime],
        any[OffsetDateTime],
        any[Option[MessageId]],
        any[Long],
        any[Source[ByteString, _]],
        eqTo(MessageStatus.Processing)
      )(any[HeaderCarrier])
    }

    "must return BAD_REQUEST when XML data extraction fails" - {

      "contains message to indicate element not found" in {

        val elementNotFoundXml: NodeSeq =
          <CC007C></CC007C>

        when(mockMovementsXmlParsingService.extractArrivalData(any[Source[ByteString, _]]))
          .thenReturn(EitherT.leftT(ParseError.NoElementFound("messageSender")))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = fakeRequest(POST, elementNotFoundXml, Some(MessageType.ArrivalNotification.code))

        val result =
          controller.createMovement(eoriNumber, MovementType.Arrival)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Element messageSender not found"
        )
      }

      "contains message to indicate too many elements found" in {

        val tooManyFoundXml: NodeSeq =
          <CC007C>
            <messageSender>GB1234</messageSender>
            <messageSender>XI1234</messageSender>
          </CC007C>

        when(mockMovementsXmlParsingService.extractArrivalData(any[Source[ByteString, _]]))
          .thenReturn(EitherT.leftT(ParseError.TooManyElementsFound("messageSender")))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = fakeRequest(POST, tooManyFoundXml, Some(MessageType.ArrivalNotification.code))

        val result =
          controller.createMovement(eoriNumber, MovementType.Arrival)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Found too many elements of type messageSender"
        )
      }

      "contains message to indicate date time failure" in {

        val tooManyFoundXml: NodeSeq =
          <CC007C>
            <messageSender>GB1234</messageSender>
            <preparationDateAndTime>no</preparationDateAndTime>
          </CC007C>

        val cs: CharSequence = "no"

        when(mockMovementsXmlParsingService.extractArrivalData(any[Source[ByteString, _]]))
          .thenReturn(
            EitherT.leftT(
              ParseError.BadDateTime("preparationDateAndTime", new DateTimeParseException("Text 'no' could not be parsed at index 0", cs, 0))
            )
          )

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = fakeRequest(POST, tooManyFoundXml, Some(MessageType.ArrivalNotification.code))

        val result =
          controller.createMovement(eoriNumber, MovementType.Arrival)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Could not parse datetime for preparationDateAndTime: Text 'no' could not be parsed at index 0"
        )
      }
    }

    "must return INTERNAL_SERVICE_ERROR" - {

      "when an invalid xml causes an unknown ParseError to be thrown" in {

        val unknownErrorXml: String =
          "<CC007C><messageSender>GB1234</messageSender>"

        when(mockMovementsXmlParsingService.extractArrivalData(any[Source[ByteString, _]]))
          .thenReturn(EitherT.leftT(ParseError.UnexpectedError(Some(new IllegalArgumentException()))))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = FakeRequest(
          method = POST,
          uri = routes.MovementsController.createMovement(eoriNumber, MovementType.Arrival).url,
          headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML, "X-Message-Type" -> MessageType.ArrivalNotification.code)),
          body = unknownErrorXml
        )

        val result =
          controller.createMovement(eoriNumber, MovementType.Arrival)(request)

        //status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )
      }

      "when file creation fails" in {

        when(mockTemporaryFileCreator.create()).thenThrow(new Exception("File creation failed"))

        val request = fakeRequest(POST, validXml, Some(MessageType.ArrivalNotification.code))

        val result =
          controller.createMovement(eoriNumber, MovementType.Arrival)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )
      }
    }
  }

  "createEmptyMovement" - {

    lazy val emptyMovement = arbitrary[Movement].sample.value.copy(
      _id = movementId,
      enrollmentEORINumber = eoriNumber,
      movementEORINumber = None,
      created = now,
      updated = now,
      messages = Vector.empty[Message]
    )

    lazy val request = FakeRequest(
      method = "POST",
      uri = routes.MovementsController.createMovement(eoriNumber, emptyMovement.movementType).url,
      headers = FakeHeaders(Seq("X-Message-Type" -> emptyMovement.movementType.value)),
      body = AnyContentAsEmpty
    )

    "must return OK if XML data extraction is successful" in {

      when(
        mockMovementFactory.createEmptyMovement(
          any[String].asInstanceOf[EORINumber],
          any[String].asInstanceOf[MovementType],
          any[Message],
          any[OffsetDateTime],
          any[OffsetDateTime]
        )
      )
        .thenReturn(movement)

      when(mockRepository.insert(any()))
        .thenReturn(EitherT.rightT(Right(())))

      val result =
        controller.createMovement(eoriNumber, MovementType.Departure)(request)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj(
        "movementId" -> movementId.value,
        "messageId"  -> messageId.value
      )
    }

    "must return INTERNAL_SERVICE_ERROR when insert fails" in {

      when(
        mockMovementFactory.createEmptyMovement(
          any[String].asInstanceOf[EORINumber],
          any[String].asInstanceOf[MovementType],
          any[Message],
          any[OffsetDateTime],
          any[OffsetDateTime]
        )
      )
        .thenReturn(movement)

      when(mockRepository.insert(any()))
        .thenReturn(EitherT.leftT(MongoError.InsertNotAcknowledged(s"Insert failed")))

      val result =
        controller.createMovement(eoriNumber, emptyMovement.movementType)(request)

      status(result) mustBe INTERNAL_SERVER_ERROR
      contentAsJson(result) mustBe Json.obj(
        "code"    -> "INTERNAL_SERVER_ERROR",
        "message" -> "Insert failed"
      )
    }
  }

  for (movementType <- Seq(MovementType.Departure, MovementType.Arrival)) {
    s"when the movement type equals ${movementType.value}" - {

      "getMovementWithoutMessages" - {

        val request = FakeRequest("GET", routes.MovementsController.getMovementWithoutMessages(eoriNumber, movementType, movementId).url)

        "must return OK if departure found" in {
          when(mockRepository.getMovementWithoutMessages(EORINumber(any()), MovementId(any()), eqTo(movementType)))
            .thenReturn(EitherT.rightT(MovementWithoutMessages.fromMovement(movement)))

          val result = controller.getMovementWithoutMessages(eoriNumber, movementType, movementId)(request)

          status(result) mustBe OK
          contentAsJson(result) mustBe Json.toJson(MovementWithoutMessages.fromMovement(movement))(PresentationFormats.movementWithoutMessagesFormat)
        }

        "must return NOT_FOUND if no departure found" in {
          when(mockRepository.getMovementWithoutMessages(EORINumber(any()), MovementId(any()), eqTo(movementType)))
            .thenReturn(EitherT.leftT(MongoError.DocumentNotFound("test")))

          val result = controller.getMovementWithoutMessages(eoriNumber, movementType, movementId)(request)

          status(result) mustBe NOT_FOUND
        }

        "must return INTERNAL_SERVER_ERROR if repository has an error" in {
          when(mockRepository.getMovementWithoutMessages(EORINumber(any()), MovementId(any()), eqTo(movementType)))
            .thenReturn(EitherT.leftT(MongoError.UnexpectedError(Some(new Throwable("test")))))

          val result = controller.getMovementWithoutMessages(eoriNumber, movementType, movementId)(request)

          status(result) mustBe INTERNAL_SERVER_ERROR
        }
      }

      "getMessage" - {
        val request = FakeRequest("GET", routes.MovementsController.getMessage(eoriNumber, movementType, movementId, messageId).url)

        "must return OK if message found in the correct format" in {
          val messageResponse = MessageResponse.fromMessageWithBody(movement.messages.head)

          when(mockRepository.getSingleMessage(EORINumber(any()), MovementId(any()), MessageId(any()), eqTo(movementType)))
            .thenReturn(EitherT.rightT(messageResponse))

          val result = controller.getMessage(eoriNumber, movementType, movementId, messageId)(request)

          status(result) mustBe OK
          contentAsJson(result) mustBe Json.toJson(messageResponse)
        }

        "must return OK along with uri if message found in the correct format" in {
          val messageResponse = MessageResponse.fromMessageWithoutBody(movement.messages.head)

          when(mockRepository.getSingleMessage(EORINumber(any()), MovementId(any()), MessageId(any()), eqTo(movementType)))
            .thenReturn(EitherT.rightT(messageResponse))

          val result = controller.getMessage(eoriNumber, movementType, movementId, messageId)(request)

          status(result) mustBe OK
          contentAsJson(result) mustBe Json.toJson(messageResponse)
        }

        "must return NOT_FOUND if no message found" in {
          when(mockRepository.getSingleMessage(EORINumber(any()), MovementId(any()), MessageId(any()), eqTo(movementType)))
            .thenReturn(EitherT.leftT(MongoError.DocumentNotFound("test")))

          val result = controller.getMessage(eoriNumber, movementType, movementId, messageId)(request)

          status(result) mustBe NOT_FOUND
        }

        "must return INTERNAL_SERVICE_ERROR when a database error is thrown" in {
          when(mockRepository.getSingleMessage(EORINumber(any()), MovementId(any()), MessageId(any()), eqTo(movementType)))
            .thenReturn(EitherT.leftT(MongoError.UnexpectedError(Some(new Throwable("test")))))

          val result = controller.getMessage(eoriNumber, movementType, movementId, messageId)(request)

          status(result) mustBe INTERNAL_SERVER_ERROR
        }
      }

      "getMessages" - {

        val request = FakeRequest("GET", routes.MovementsController.getMessages(eoriNumber, movementType, movementId).url)

        "must return OK and a list of message ids" in {
          val messageResponses = MessageResponse.fromMessageWithoutBody(movement.messages.head)

          lazy val messageResponseList = Vector(messageResponses)

          when(mockRepository.getMessages(EORINumber(any()), MovementId(any()), eqTo(movementType), eqTo(None)))
            .thenReturn(EitherT.rightT(Vector(messageResponses)))

          val result = controller.getMessages(eoriNumber, movementType, movementId, None)(request)

          status(result) mustBe OK
          contentAsJson(result) mustBe Json.toJson(messageResponseList)
        }

        "must return NOT_FOUND if no departure found" in {
          when(mockRepository.getMessages(EORINumber(any()), MovementId(any()), eqTo(movementType), eqTo(None)))
            .thenReturn(EitherT.rightT(Vector.empty[MessageResponse]))

          val result = controller.getMessages(eoriNumber, movementType, movementId, None)(request)

          status(result) mustBe NOT_FOUND
        }

        "must return INTERNAL_SERVER_ERROR when a database error is thrown" in {
          when(mockRepository.getMessages(EORINumber(any()), MovementId(any()), eqTo(movementType), eqTo(None)))
            .thenReturn(EitherT.leftT(UnexpectedError(None)))

          val result = controller.getMessages(eoriNumber, movementType, movementId, None)(request)

          status(result) mustBe INTERNAL_SERVER_ERROR
        }
      }

      "getMovementsForEori" - {
        val request = FakeRequest("GET", routes.MovementsController.getMovementsForEori(eoriNumber, movementType).url)

        "must return OK if departures were found" in {
          val response = MovementWithoutMessages.fromMovement(movement)

          when(mockRepository.getMovements(EORINumber(any()), eqTo(movementType), eqTo(None), eqTo(None), eqTo(None)))
            .thenReturn(EitherT.rightT(Vector(response)))

          val result = controller.getMovementsForEori(eoriNumber, movementType)(request)
          status(result) mustBe OK
          contentAsJson(result) mustBe Json.toJson(Vector(response))
        }

        "must return OK if departures were found and it match the updatedSince or movementEORI or movementReferenceNumber or all these filters" in forAll(
          Gen.option(arbitrary[OffsetDateTime]),
          Gen.option(arbitrary[EORINumber]),
          Gen.option(arbitrary[MovementReferenceNumber])
        ) {
          (updatedSince, movementEORI, movementReferenceNumber) =>
            val response = MovementWithoutMessages.fromMovement(movement)

            when(mockRepository.getMovements(EORINumber(any()), eqTo(movementType), eqTo(updatedSince), eqTo(movementEORI), eqTo(movementReferenceNumber)))
              .thenReturn(EitherT.rightT(Vector(response)))

            val result = controller.getMovementsForEori(eoriNumber, movementType, updatedSince, movementEORI, movementReferenceNumber)(request)

            status(result) mustBe OK
            contentAsJson(result) mustBe Json.toJson(Vector(response))
        }

        "must return NOT_FOUND if no ids were found" in forAll(Gen.option(arbitrary[OffsetDateTime]), Gen.option(arbitrary[EORINumber])) {
          (updatedSince, movementEORI) =>
            when(mockRepository.getMovements(EORINumber(any()), eqTo(movementType), eqTo(updatedSince), eqTo(movementEORI), eqTo(None)))
              .thenReturn(EitherT.rightT(Vector.empty[MovementWithoutMessages]))

            val result = controller.getMovementsForEori(eoriNumber, movementType, updatedSince, movementEORI)(request)

            status(result) mustBe NOT_FOUND
        }

        "must return INTERNAL_SERVICE_ERROR when a database error is thrown" in forAll(
          Gen.option(arbitrary[OffsetDateTime]),
          Gen.option(arbitrary[EORINumber])
        ) {
          (updatedSince, movementEORI) =>
            when(mockRepository.getMovements(EORINumber(any()), any(), eqTo(updatedSince), eqTo(movementEORI), eqTo(None)))
              .thenReturn(EitherT.leftT(MongoError.UnexpectedError(Some(new Throwable("test")))))

            val result = controller.getMovementsForEori(eoriNumber, movementType, updatedSince, movementEORI)(request)

            status(result) mustBe INTERNAL_SERVER_ERROR
        }
      }
    }
  }

  "updateMovement" - {

    val messageType = MessageType.DeclarationData

    lazy val messageData: MessageData = MessageData(OffsetDateTime.now(ZoneId.of("UTC")), None)

    lazy val messageDataEither: EitherT[Future, ParseError, MessageData] =
      EitherT.rightT(messageData)

    val validXml: NodeSeq =
      <CC015C>
        <messageSender>ABC123</messageSender>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
      </CC015C>

    val validXmlStream = Source.single(ByteString(validXml.mkString))

    lazy val messageFactoryEither: EitherT[Future, StreamError, Message] =
      EitherT.rightT(message)

    "must return OK if XML data extraction is successful" in {

      val tempFile = SingletonTemporaryFileCreator.create()
      when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

      when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], any[MessageType]))
        .thenReturn(messageDataEither)

      when(
        mockMessageFactory.create(
          MovementId(eqTo(movementId.value)),
          any[MessageType],
          any[OffsetDateTime],
          any[OffsetDateTime],
          any[Option[MessageId]],
          any[Long],
          any[Source[ByteString, _]],
          any[MessageStatus]
        )(any[HeaderCarrier])
      )
        .thenReturn(messageFactoryEither)

      when(mockRepository.attachMessage(any[String].asInstanceOf[MovementId], any[Message], any[Option[MovementReferenceNumber]], any[OffsetDateTime]))
        .thenReturn(EitherT.rightT(()))

      val request = fakeRequest(POST, validXmlStream, Some(messageType.code))

      val result: Future[Result] =
        controller.updateMovement(movementId, Some(triggerId))(request)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj("messageId" -> messageId.value)
    }

    "must return BAD_REQUEST when XML data extraction fails" - {

      "contains message to indicate date time failure" in {

        val cs: CharSequence = "invalid"

        val xml: NodeSeq =
          <CC009C>
            <preparationDateAndTime>invalid</preparationDateAndTime>
          </CC009C>

        val xmlStream = Source.single(ByteString(xml.mkString))

        val tempFile = SingletonTemporaryFileCreator.create()
        when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

        when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], any[MessageType]))
          .thenReturn(
            EitherT.leftT(
              ParseError.BadDateTime("preparationDateAndTime", new DateTimeParseException("Text 'invalid' could not be parsed at index 0", cs, 0))
            )
          )

        val request = fakeRequest(POST, xmlStream, Some(messageType.code))

        val result: Future[Result] =
          controller.updateMovement(movementId, Some(triggerId))(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Could not parse datetime for preparationDateAndTime: Text 'invalid' could not be parsed at index 0"
        )
      }

      "contains message to indicate update failed due to document with given id not found" in {

        val tempFile = SingletonTemporaryFileCreator.create()
        when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

        when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], any[MessageType]))
          .thenReturn(messageDataEither)

        when(
          mockMessageFactory.create(
            MovementId(eqTo(movementId.value)),
            any[MessageType],
            any[OffsetDateTime],
            any[OffsetDateTime],
            eqTo(Some(triggerId)),
            any[Long],
            any[Source[ByteString, _]],
            any[MessageStatus]
          )(any[HeaderCarrier])
        )
          .thenReturn(messageFactoryEither)

        when(mockRepository.attachMessage(any[String].asInstanceOf[MovementId], any[Message], any[Option[MovementReferenceNumber]], any[OffsetDateTime]))
          .thenReturn(EitherT.leftT(MongoError.DocumentNotFound(s"No departure found with the given id: ${movementId.value}")))

        val request = fakeRequest(POST, validXmlStream, Some(messageType.code))

        val result: Future[Result] =
          controller.updateMovement(movementId, Some(triggerId))(request)

        status(result) mustBe NOT_FOUND
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "NOT_FOUND",
          "message" -> s"No departure found with the given id: ${movementId.value}"
        )
      }

      "contains message to indicate message type header not supplied" in {

        val tempFile = SingletonTemporaryFileCreator.create()
        when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

        val request = fakeRequest(POST, validXml, None, FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML)))

        val result =
          controller.updateMovement(movementId, Some(triggerId))(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Missing X-Message-Type header value"
        )
      }

      "contains message to indicate the given message type is invalid" in {

        val tempFile = SingletonTemporaryFileCreator.create()
        when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

        val request = fakeRequest(POST, validXml, Some("invalid"), FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML)))

        val result =
          controller.updateMovement(movementId, Some(triggerId))(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Invalid X-Message-Type header value: invalid"
        )
      }

    }

    "must return INTERNAL_SERVICE_ERROR" - {

      "when an invalid xml causes an unknown ParseError to be thrown" in {

        val unknownErrorXml: String =
          "<CC007C><messageSender/>GB1234"

        when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], any[MessageType]))
          .thenReturn(EitherT.leftT(ParseError.UnexpectedError(Some(new IllegalArgumentException()))))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = FakeRequest(
          method = POST,
          uri = routes.MovementsController.updateMovement(movementId, Some(triggerId)).url,
          headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML, "X-Message-Type" -> messageType.code)),
          body = unknownErrorXml
        )

        val result =
          controller.updateMovement(movementId, Some(triggerId))(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )
      }

      "when file creation fails" in {

        when(mockTemporaryFileCreator.create()).thenThrow(new Exception("File creation failed"))

        val request = fakeRequest(POST, validXml, Some(messageType.code))

        val result =
          controller.updateMovement(movementId, Some(triggerId))(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )
      }
    }
  }

  "updateMovement for an empty message" - {

    lazy val message =
      arbitraryMessage.arbitrary.sample.get.copy(
        generated = None,
        messageType = None,
        triggerId = None,
        uri = None,
        body = None,
        status = Some(MessageStatus.Pending)
      )

    lazy val request = FakeRequest(
      method = "POST",
      uri = routes.MovementsController.updateMovement(movementId, None).url,
      headers = FakeHeaders(Seq.empty[(String, String)]),
      body = AnyContentAsEmpty
    )

    "must return OK if successfully attaches an empty message to a movement" in {

      when(
        mockMessageFactory.createEmptyMessage(
          any[Option[MessageType]],
          any[OffsetDateTime]
        )
      )
        .thenReturn(message)

      when(mockRepository.attachMessage(any[String].asInstanceOf[MovementId], any[Message], any[Option[MovementReferenceNumber]], any[OffsetDateTime]))
        .thenReturn(EitherT.rightT(()))

      val result =
        controller.updateMovement(movementId, None)(request)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj("messageId" -> message.id.value)
    }

    "must return NOT_FOUND when movement cannot be found in DB" in {

      when(
        mockMessageFactory.createEmptyMessage(
          any[Option[MessageType]],
          any[OffsetDateTime]
        )
      )
        .thenReturn(message)

      val errorMessage = s"Movement with id ${movementId.value} not found"

      when(mockRepository.attachMessage(any[String].asInstanceOf[MovementId], any[Message], any[Option[MovementReferenceNumber]], any[OffsetDateTime]))
        .thenReturn(EitherT.leftT(MongoError.DocumentNotFound(errorMessage)))

      val result =
        controller.updateMovement(movementId, None)(request)

      status(result) mustBe NOT_FOUND
      contentAsJson(result) mustBe Json.obj(
        "code"    -> "NOT_FOUND",
        "message" -> errorMessage
      )
    }

    "must return INTERNAL_SERVER_ERROR when an unexpected failure happens during message creation in the DB" in {

      when(
        mockMessageFactory.createEmptyMessage(
          any[Option[MessageType]],
          any[OffsetDateTime]
        )
      )
        .thenReturn(message)

      when(mockRepository.attachMessage(any[String].asInstanceOf[MovementId], any[Message], any[Option[MovementReferenceNumber]], any[OffsetDateTime]))
        .thenReturn(EitherT.leftT(MongoError.UnexpectedError(None)))

      val result =
        controller.updateMovement(movementId, None)(request)

      status(result) mustBe INTERNAL_SERVER_ERROR
      contentAsJson(result) mustBe Json.obj(
        "code"    -> "INTERNAL_SERVER_ERROR",
        "message" -> "Internal server error"
      )
    }

  }

  "updateMovement for large messages" - {

    val messageType = MessageType.DeclarationData

    lazy val messageData: MessageData = MessageData(OffsetDateTime.now(ZoneId.of("UTC")), None)

    lazy val messageDataEither: EitherT[Future, ParseError, MessageData] =
      EitherT.rightT(messageData)

    lazy val messageFactory =
      arbitraryMessage.arbitrary.sample.get.copy(id = messageId, generated = Some(now), received = now, triggerId = Some(triggerId), uri = Some(objectStoreURI))

    "must return OK if XML data extraction is successful" in {

      when(mockObjectStoreService.getObjectStoreFile(any[String].asInstanceOf[ObjectStoreResourceLocation])(any[ExecutionContext], any[HeaderCarrier]))
        .thenReturn(EitherT.rightT(Source.single(ByteString("this is test content"))))

      when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], any[MessageType]))
        .thenReturn(messageDataEither)

      when(
        mockMessageFactory.create(
          MovementId(eqTo(movementId.value)),
          any[MessageType],
          any[OffsetDateTime],
          any[OffsetDateTime],
          any[Option[MessageId]],
          any[String].asInstanceOf[ObjectStoreURI],
          eqTo(messageType.statusOnAttach)
        )
      )
        .thenReturn(messageFactory)

      when(mockRepository.attachMessage(any[String].asInstanceOf[MovementId], any[Message], any[Option[MovementReferenceNumber]], any[OffsetDateTime]))
        .thenReturn(EitherT.rightT(()))

      lazy val request = FakeRequest(
        method = "POST",
        uri = routes.MovementsController.updateMovement(movementId, Some(triggerId)).url,
        headers = FakeHeaders(Seq("X-Message-Type" -> messageType.code, "X-Object-Store-Uri" -> ObjectStoreResourceLocation(filePath).value)),
        body = AnyContentAsEmpty
      )

      val result =
        controller.updateMovement(movementId, Some(triggerId))(request)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj("messageId" -> messageId.value)
    }

    "must return BAD_REQUEST when file not found on object store resource location" in {

      when(mockObjectStoreService.getObjectStoreFile(any[String].asInstanceOf[ObjectStoreResourceLocation])(any[ExecutionContext], any[HeaderCarrier]))
        .thenReturn(EitherT.leftT(ObjectStoreError.FileNotFound(filePath)))
      lazy val request = FakeRequest(
        method = "POST",
        uri = routes.MovementsController.updateMovement(movementId, Some(triggerId)).url,
        headers = FakeHeaders(Seq("X-Message-Type" -> messageType.code, "X-Object-Store-Uri" -> filePath)),
        body = AnyContentAsEmpty
      )

      val result =
        controller.updateMovement(movementId, Some(triggerId))(request)

      status(result) mustBe BAD_REQUEST
      contentAsJson(result) mustBe Json.obj(
        "code"    -> "BAD_REQUEST",
        "message" -> s"file not found at location: $filePath"
      )
    }

    "must return INTERNAL_SERVICE_ERROR when an invalid xml causes an unknown ParseError to be thrown" in {

      when(mockObjectStoreService.getObjectStoreFile(any[String].asInstanceOf[ObjectStoreResourceLocation])(any[ExecutionContext], any[HeaderCarrier]))
        .thenReturn(EitherT.rightT(Source.single(ByteString("this is test content"))))

      when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], any[MessageType]))
        .thenReturn(EitherT.leftT(ParseError.UnexpectedError(Some(new IllegalArgumentException()))))

      lazy val request = FakeRequest(
        method = "POST",
        uri = routes.MovementsController.updateMovement(movementId, Some(triggerId)).url,
        headers = FakeHeaders(Seq("X-Message-Type" -> messageType.code, "X-Object-Store-Uri" -> filePath)),
        body = AnyContentAsEmpty
      )

      val result =
        controller.updateMovement(movementId, Some(triggerId))(request)

      status(result) mustBe INTERNAL_SERVER_ERROR
      contentAsJson(result) mustBe Json.obj(
        "code"    -> "INTERNAL_SERVER_ERROR",
        "message" -> "Internal server error"
      )
    }
  }

  "updateMessage" - {

    "for requests initiated from the trader or Upscan" - {

      "must return OK if successful given an object store URI, message type and status" - {

        "if the message is a departure message and the first one in the movement" in forAll(
          arbitrary[EORINumber],
          arbitrary[MovementId],
          arbitrary[MessageId],
          Gen.oneOf(MessageType.departureRequestValues)
        ) {
          (eori, movementId, messageId, messageType) =>
            val tempFile = SingletonTemporaryFileCreator.create()
            when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

            when(
              mockRepository.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(MovementType.Departure))
            )
              .thenReturn(EitherT.rightT(MovementWithoutMessages(movementId, eori, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock))))

            when(
              mockRepository.getSingleMessage(
                EORINumber(eqTo(eori.value)),
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                eqTo(MovementType.Departure)
              )
            )
              .thenReturn(EitherT.rightT(MessageResponse(messageId, now, None, None, Some(MessageStatus.Pending), None)))

            when(
              mockObjectStoreService
                .getObjectStoreFile(ObjectStoreResourceLocation(eqTo("movements/abcdef0123456789/abc.xml")))(any[ExecutionContext], any[HeaderCarrier])
            )
              .thenReturn(EitherT.rightT(Source.empty[ByteString]))

            when(
              mockMovementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, _]])
            )
              .thenReturn(EitherT.rightT(Some(DeclarationData(Some(eori), OffsetDateTime.now(clock)))))

            when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(messageType)))
              .thenReturn(EitherT.rightT(MessageData(generatedTime, None)))

            when(
              mockRepository.updateMessage(
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                argThat(
                  UpdateMessageDataMatcher(
                    Some(ObjectStoreURI("common-transit-convention-traders/movements/abcdef0123456789/abc.xml")),
                    None,
                    MessageStatus.Success,
                    Some(messageType),
                    Some(generatedTime),
                    expectSize = false
                  )
                ),
                any[OffsetDateTime]
              )
            )
              .thenReturn(EitherT.rightT(()))

            when(
              mockRepository.updateMovement(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(None), any[OffsetDateTime])
            )
              .thenReturn(EitherT.rightT(()))

            val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
            val body = Json.obj(
              "messageType"    -> messageType.code,
              "objectStoreURI" -> "common-transit-convention-traders/movements/abcdef0123456789/abc.xml",
              "status"         -> "Success"
            )
            val request = FakeRequest(
              method = POST,
              uri = routes.MovementsController.updateMessage(eori, MovementType.Arrival, movementId, messageId).url,
              headers = headers,
              body = body
            )

            val result =
              controller.updateMessage(eori, MovementType.Departure, movementId, messageId)(request)

            status(result) mustBe OK
            verify(mockRepository, times(1)).updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              any[UpdateMessageData],
              any[OffsetDateTime]
            )
            verify(mockRepository, times(1)).updateMovement(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(None), any[OffsetDateTime])
        }

        "if the message is an arrival message and the first one in the movement" in forAll(
          arbitrary[EORINumber],
          arbitrary[MovementId],
          arbitrary[MessageId],
          arbitrary[MovementReferenceNumber],
          Gen.oneOf(MessageType.arrivalRequestValues)
        ) {
          (eori, movementId, messageId, mrn, messageType) =>
            val tempFile = SingletonTemporaryFileCreator.create()
            when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

            when(
              mockRepository.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(MovementType.Arrival))
            )
              .thenReturn(EitherT.rightT(MovementWithoutMessages(movementId, eori, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock))))

            when(
              mockRepository.getSingleMessage(
                EORINumber(eqTo(eori.value)),
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                eqTo(MovementType.Arrival)
              )
            )
              .thenReturn(EitherT.rightT(MessageResponse(messageId, now, Some(messageType), None, Some(MessageStatus.Pending), None)))

            when(
              mockObjectStoreService
                .getObjectStoreFile(ObjectStoreResourceLocation(eqTo("movements/abcdef0123456789/abc.xml")))(any[ExecutionContext], any[HeaderCarrier])
            )
              .thenReturn(EitherT.rightT(Source.empty[ByteString]))

            when(
              mockMovementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, _]])
            )
              .thenReturn(EitherT.rightT(Some(ArrivalData(Some(eori), OffsetDateTime.now(clock), mrn))))

            when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(messageType)))
              .thenReturn(EitherT.rightT(MessageData(generatedTime, Some(mrn))))

            when(
              mockRepository.updateMessage(
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                argThat(
                  UpdateMessageDataMatcher(
                    Some(ObjectStoreURI("common-transit-convention-traders/movements/abcdef0123456789/abc.xml")),
                    None,
                    MessageStatus.Success,
                    Some(messageType),
                    Some(generatedTime),
                    expectSize = false
                  )
                ),
                any[OffsetDateTime]
              )
            )
              .thenReturn(EitherT.rightT(()))

            when(
              mockRepository.updateMovement(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(Some(mrn)), any[OffsetDateTime])
            )
              .thenReturn(EitherT.rightT(()))

            val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
            val body = Json.obj(
              "messageType"    -> messageType.code,
              "objectStoreURI" -> "common-transit-convention-traders/movements/abcdef0123456789/abc.xml",
              "status"         -> "Success"
            )
            val request = FakeRequest(
              method = POST,
              uri = routes.MovementsController.updateMessage(eori, MovementType.Departure, movementId, messageId).url,
              headers = headers,
              body = body
            )

            val result =
              controller.updateMessage(eori, MovementType.Arrival, movementId, messageId)(request)

            status(result) mustBe OK
            verify(mockRepository, times(1)).updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              any[UpdateMessageData],
              any[OffsetDateTime]
            )
            verify(mockRepository, times(1)).updateMovement(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(Some(mrn)), any[OffsetDateTime])
        }

        "if the movement EORI has already been set" in forAll(
          arbitrary[EORINumber],
          arbitrary[MovementId],
          arbitrary[MessageId],
          arbitrary[MovementType]
        ) {
          (eori, movementId, messageId, movementType) =>
            val tempFile = SingletonTemporaryFileCreator.create()
            when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

            val messageType: MessageType = {
              if (movementType == MovementType.Departure) Gen.oneOf(MessageType.departureRequestValues)
              else Gen.oneOf(MessageType.arrivalRequestValues)
            }.sample.get
            when(
              mockRepository.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(movementType))
            )
              .thenReturn(
                EitherT.rightT(MovementWithoutMessages(movementId, eori, Some(eori), None, OffsetDateTime.now(clock), OffsetDateTime.now(clock)))
              )

            when(
              mockObjectStoreService
                .getObjectStoreFile(ObjectStoreResourceLocation(eqTo("movements/abcdef0123456789/abc.xml")))(any[ExecutionContext], any[HeaderCarrier])
            )
              .thenReturn(EitherT.rightT(Source.empty[ByteString]))

            when(
              mockRepository.getSingleMessage(
                EORINumber(eqTo(eori.value)),
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                eqTo(movementType)
              )
            )
              .thenReturn(EitherT.rightT(MessageResponse(messageId, now, Some(messageType), None, Some(MessageStatus.Pending), None)))

            when(
              mockRepository.updateMessage(
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                any[UpdateMessageData],
                any[OffsetDateTime]
              )
            )
              .thenReturn(EitherT.rightT(()))

            when(
              mockMovementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, _]])
            )
              .thenReturn(EitherT.rightT(Some(DeclarationData(Some(eori), OffsetDateTime.now(clock)))))

            when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(messageType)))
              .thenReturn(EitherT.rightT(MessageData(generatedTime, None)))

            when(
              mockRepository.updateMovement(MovementId(eqTo(movementId.value)), eqTo(None), eqTo(None), any[OffsetDateTime])
            )
              .thenReturn(EitherT.rightT(()))

            val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
            val body = Json.obj(
              "objectStoreURI" -> "common-transit-convention-traders/movements/abcdef0123456789/abc.xml",
              "status"         -> "Success",
              "messageType"    -> messageType.code
            )
            val request = FakeRequest(
              method = POST,
              uri = routes.MovementsController.updateMessage(eori, movementType, movementId, messageId).url,
              headers = headers,
              body = body
            )

            val result =
              controller.updateMessage(eori, movementType, movementId, messageId)(request)

            status(result) mustBe OK
            verify(mockRepository, times(1)).updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              any[UpdateMessageData],
              any[OffsetDateTime]
            )
            verify(mockRepository, times(1)).updateMovement(MovementId(eqTo(movementId.value)), eqTo(None), eqTo(None), any[OffsetDateTime])
        }
      }

      "must return Bad Request" - {

        "if the object store URI is not a common-transit-convention-traders owned URI" in forAll(
          arbitrary[EORINumber],
          arbitrary[MovementId],
          arbitrary[MessageId],
          arbitrary[MovementType]
        ) {
          (eori, movementId, messageId, movementType) =>
            val messageType: MessageType = {
              if (movementType == MovementType.Departure) Gen.oneOf(MessageType.departureRequestValues)
              else Gen.oneOf(MessageType.arrivalRequestValues)
            }.sample.get

            when(
              mockRepository.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(movementType))
            )
              .thenReturn(EitherT.rightT(MovementWithoutMessages(movementId, eori, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock))))

            when(
              mockRepository.getSingleMessage(
                EORINumber(eqTo(eori.value)),
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                eqTo(movementType)
              )
            )
              .thenReturn(EitherT.rightT(MessageResponse(messageId, now, Some(messageType), None, Some(MessageStatus.Pending), None)))

            when(
              mockObjectStoreService
                .getObjectStoreFile(ObjectStoreResourceLocation(eqTo("movements/abcdef0123456789/abc.xml")))(any[ExecutionContext], any[HeaderCarrier])
            )
              .thenReturn(EitherT.rightT(Source.empty[ByteString]))

            when(
              mockMovementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, _]])
            )
              .thenReturn(EitherT.rightT(Some(DeclarationData(Some(eori), OffsetDateTime.now(clock)))))

            when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(messageType)))
              .thenReturn(EitherT.rightT(MessageData(generatedTime, Some(mrn))))

            when(
              mockRepository.updateMessage(
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                any[UpdateMessageData],
                any[OffsetDateTime]
              )
            )
              .thenReturn(EitherT.rightT(()))

            when(
              mockRepository.updateMovement(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(None), any[OffsetDateTime])
            )
              .thenReturn(EitherT.rightT(()))

            val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
            val body = Json.obj(
              "objectStoreURI" -> "something/movements/abcdef0123456789/abc.xml",
              "status"         -> "Success",
              "messageType"    -> messageType.code
            )
            val request = FakeRequest(
              method = POST,
              uri = routes.MovementsController.updateMessage(eori, movementType, movementId, messageId).url,
              headers = headers,
              body = body
            )

            val result =
              controller.updateMessage(eori, movementType, movementId, messageId)(request)

            status(result) mustBe BAD_REQUEST
            contentAsJson(result) mustBe Json.obj(
              "code"    -> "BAD_REQUEST",
              "message" -> "Provided Object Store URI is not owned by common-transit-convention-traders"
            )
            verify(mockRepository, times(0)).updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              any[UpdateMessageData],
              any[OffsetDateTime]
            )
            verify(mockRepository, times(0)).updateMovement(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(None), any[OffsetDateTime])
        }
      }

      "must return OK, if the update message is successful, given only status is provided in the request" in forAll(
        arbitrary[EORINumber],
        arbitrary[MovementType],
        arbitrary[MessageStatus],
        arbitrary[MessageType]
      ) {
        (eori, movementType, messageStatus, messageType) =>
          reset(mockRepository) // needed thanks to the generators running the test multiple times.
          val expectedUpdateData = UpdateMessageData(status = messageStatus)

          when(
            mockRepository.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(movementType))
          )
            .thenReturn(
              EitherT.rightT(MovementWithoutMessages(movementId, eori, Some(eori), None, OffsetDateTime.now(clock), OffsetDateTime.now(clock)))
            )

          when(
            mockRepository.getSingleMessage(
              EORINumber(eqTo(eori.value)),
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              eqTo(movementType)
            )
          )
            .thenReturn(EitherT.rightT(MessageResponse(messageId, now, Some(messageType), None, Some(MessageStatus.Pending), None)))

          when(
            mockRepository.updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              eqTo(expectedUpdateData),
              any[OffsetDateTime]
            )
          )
            .thenReturn(EitherT.rightT(()))

          val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
          val body = Json.obj(
            "status" -> messageStatus.toString
          )
          val request = FakeRequest(
            method = POST,
            uri = routes.MovementsController.updateMessage(eori, movementType, movementId, messageId).url,
            headers = headers,
            body = body
          )

          val result =
            controller.updateMessage(eori, movementType, movementId, messageId)(request)

          status(result) mustBe OK
          verify(mockRepository, times(1)).updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            any[UpdateMessageData],
            any[OffsetDateTime]
          )
          verify(mockRepository, times(0)).updateMovement(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(None), any[OffsetDateTime])
      }

      "must return BAD_REQUEST when JSON data extraction fails" in forAll(arbitrary[EORINumber], arbitrary[MovementType]) {
        (eori, messageType) =>
          val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
          val body = Json.obj(
            "objectStoreURI" -> "common-transit-convention-traders/something.xml"
          )
          val request = FakeRequest(
            method = POST,
            uri = routes.MovementsController.updateMessage(eori, messageType, movementId, messageId).url,
            headers = headers,
            body = body
          )

          val result =
            controller.updateMessage(eori, messageType, movementId, messageId)(request)

          status(result) mustBe BAD_REQUEST
          contentAsJson(result) mustBe Json.obj(
            "code"    -> "BAD_REQUEST",
            "message" -> "Could not parse the request"
          )
      }

      "must return BAD REQUEST, if the update message is unsuccessful, given invalid status is provided in the request" in forAll(
        arbitrary[EORINumber],
        arbitrary[MovementType]
      ) {
        (eori, messageType) =>
          when(
            mockRepository.updateMessage(
              any[String].asInstanceOf[MovementId],
              any[String].asInstanceOf[MessageId],
              any[UpdateMessageData],
              any[OffsetDateTime]
            )
          )
            .thenReturn(EitherT.rightT(()))

          val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
          val body = Json.obj(
            "status" -> "123"
          )
          val request = FakeRequest(
            method = POST,
            uri = routes.MovementsController.updateMessage(eori, messageType, movementId, messageId).url,
            headers = headers,
            body = body
          )

          val result =
            controller.updateMessage(eori, messageType, movementId, messageId)(request)

          status(result) mustBe BAD_REQUEST
      }

      "must return OK if successful given both MessageType and status" - {

        "if the message is a departure message and the first one in the movement" in forAll(
          arbitrary[EORINumber],
          arbitrary[MovementId],
          arbitrary[MessageId]
        ) {
          (eori, movementId, messageId) =>
            val movementType = MovementType.Departure
            when(
              mockRepository.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(MovementType.Departure))
            )
              .thenReturn(EitherT.rightT(MovementWithoutMessages(movementId, eori, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock))))

            when(
              mockRepository.getSingleMessage(
                EORINumber(eqTo(eori.value)),
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                eqTo(movementType)
              )
            )
              .thenReturn(EitherT.rightT(MessageResponse(messageId, now, None, None, Some(MessageStatus.Pending), None)))

            when(
              mockMovementsXmlParsingService.extractData(eqTo(MovementType.Departure), any[Source[ByteString, _]])
            )
              .thenReturn(EitherT.rightT(DeclarationData(Some(eori), OffsetDateTime.now(clock))))

            when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(MessageType.DeclarationData)))
              .thenReturn(EitherT.rightT(MessageData(generatedTime, Some(mrn))))

            when(
              mockRepository.updateMessage(
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                any[UpdateMessageData],
                any[OffsetDateTime]
              )
            )
              .thenReturn(EitherT.rightT(()))

            val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
            val body = Json.obj(
              "status"      -> "Success",
              "messageType" -> "IE015"
            )
            val request = FakeRequest(
              method = POST,
              uri = routes.MovementsController.updateMessage(eori, MovementType.Arrival, movementId, messageId).url,
              headers = headers,
              body = body
            )

            val result = controller.updateMessage(eori, MovementType.Departure, movementId, messageId)(request)

            status(result) mustBe OK
            verify(mockRepository, times(1)).updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              any[UpdateMessageData],
              any[OffsetDateTime]
            )
            verify(mockRepository, times(0)).updateMovement(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(None), any[OffsetDateTime])
        }

        "if the message is an arrival message and the first one in the movement" in forAll(
          arbitrary[EORINumber],
          arbitrary[MovementId],
          arbitrary[MessageId],
          arbitrary[MovementReferenceNumber]
        ) {
          (eori, movementId, messageId, mrn) =>
            val movementType = MovementType.Arrival
            val messageType  = MessageType.ArrivalNotification
            when(
              mockRepository.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(MovementType.Arrival))
            )
              .thenReturn(EitherT.rightT(MovementWithoutMessages(movementId, eori, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock))))

            when(
              mockRepository.getSingleMessage(
                EORINumber(eqTo(eori.value)),
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                eqTo(movementType)
              )
            )
              .thenReturn(EitherT.rightT(MessageResponse(messageId, now, Some(messageType), None, Some(MessageStatus.Pending), None)))

            when(
              mockRepository.updateMessage(
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                any[UpdateMessageData],
                any[OffsetDateTime]
              )
            )
              .thenReturn(EitherT.rightT(()))

            val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
            val body = Json.obj(
              "status"      -> "Success",
              "messageType" -> "IE007"
            )
            val request = FakeRequest(
              method = POST,
              uri = routes.MovementsController.updateMessage(eori, MovementType.Departure, movementId, messageId).url,
              headers = headers,
              body = body
            )

            val result =
              controller.updateMessage(eori, MovementType.Arrival, movementId, messageId)(request)

            status(result) mustBe OK
            verify(mockRepository, times(1)).updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              any[UpdateMessageData],
              any[OffsetDateTime]
            )
            verify(mockRepository, times(0)).updateMovement(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(Some(mrn)), any[OffsetDateTime])
        }

      }

      "must return BAD_REQUEST given invalid messageType for departure message" in forAll(arbitrary[EORINumber]) {
        eori =>
          val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
          val body = Json.obj(
            "status"      -> "Success",
            "messageType" -> "IE007"
          )
          val request = FakeRequest(
            method = POST,
            uri = routes.MovementsController.updateMessage(eori, MovementType.Departure, movementId, messageId).url,
            headers = headers,
            body = body
          )

          val result =
            controller.updateMessage(eori, MovementType.Departure, movementId, messageId)(request)

          status(result) mustBe BAD_REQUEST
          contentAsJson(result) mustBe Json.obj(
            "code"    -> "BAD_REQUEST",
            "message" -> "Invalid messageType value: ArrivalNotification"
          )
      }

      "must return BAD_REQUEST if the message type does not match the expected type, if one exists" in forAll(
        arbitrary[EORINumber],
        arbitrary[MovementId],
        arbitrary[MessageId],
        Gen.oneOf(MessageType.departureRequestValues)
      ) {
        (eori, movementId, messageId, messageType) =>
          val movementType = MovementType.Departure

          when(
            mockRepository.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(MovementType.Departure))
          )
            .thenReturn(EitherT.rightT(MovementWithoutMessages(movementId, eori, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock))))

          when(
            mockRepository.getSingleMessage(
              EORINumber(eqTo(eori.value)),
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              eqTo(movementType)
            )
          )
            .thenReturn(EitherT.rightT(MessageResponse(messageId, now, Some(MessageType.ArrivalNotification), None, Some(MessageStatus.Pending), None)))

          when(
            mockMovementsXmlParsingService.extractData(eqTo(MovementType.Departure), any[Source[ByteString, _]])
          )
            .thenReturn(EitherT.rightT(DeclarationData(Some(eori), OffsetDateTime.now(clock))))

          when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(MessageType.DeclarationData)))
            .thenReturn(EitherT.rightT(MessageData(generatedTime, Some(mrn))))

          when(
            mockRepository.updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              any[UpdateMessageData],
              any[OffsetDateTime]
            )
          )
            .thenReturn(EitherT.rightT(()))

          val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
          val body = Json.obj(
            "objectStoreURI" -> "common-transit-convention-traders/test/abc.xml",
            "status"         -> "Success",
            "messageType"    -> messageType.code
          )
          val request = FakeRequest(
            method = POST,
            uri = routes.MovementsController.updateMessage(eori, MovementType.Departure, movementId, messageId).url,
            headers = headers,
            body = body
          )

          val result = controller.updateMessage(eori, MovementType.Departure, movementId, messageId)(request)

          status(result) mustBe BAD_REQUEST
          contentAsJson(result) mustBe Json.obj(
            "code"    -> "BAD_REQUEST",
            "message" -> "Message type does not match"
          )
          verify(mockRepository, times(0)).updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            any[UpdateMessageData],
            any[OffsetDateTime]
          )
          verify(mockRepository, times(0)).updateMovement(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(None), any[OffsetDateTime])
      }

      "must return BAD_REQUEST given invalid messageType for arrival message" in forAll(arbitrary[EORINumber]) {
        eori =>
          val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
          val body = Json.obj(
            "status"      -> "Success",
            "messageType" -> "IE015"
          )
          val request = FakeRequest(
            method = POST,
            uri = routes.MovementsController.updateMessage(eori, MovementType.Arrival, movementId, messageId).url,
            headers = headers,
            body = body
          )

          val result =
            controller.updateMessage(eori, MovementType.Arrival, movementId, messageId)(request)

          status(result) mustBe BAD_REQUEST
          contentAsJson(result) mustBe Json.obj(
            "code"    -> "BAD_REQUEST",
            "message" -> "Invalid messageType value: DeclarationData"
          )
      }
    }
  }

  "updateMessageStatus for messages initiated by SDES" - {
    "must return OK, if the update status message is successful" in forAll(
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[MessageStatus]
    ) {
      (movementId, messageId, messageStatus) =>
        reset(mockRepository) // needed thanks to the generators running the test multiple times.
        val expectedUpdateMessageMetadata = UpdateMessageData(status = messageStatus)

        when(
          mockRepository.updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(expectedUpdateMessageMetadata),
            any[OffsetDateTime]
          )
        )
          .thenReturn(EitherT.rightT(()))

        val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
        val body = Json.obj(
          "status" -> messageStatus.toString
        )
        val request = FakeRequest(
          method = POST,
          uri = routes.MovementsController.updateMessageStatus(movementId, messageId).url,
          headers = headers,
          body = body
        )

        val result =
          controller.updateMessageStatus(movementId, messageId)(request)

        status(result) mustBe OK
        verify(mockRepository, times(1)).updateMessage(
          MovementId(eqTo(movementId.value)),
          MessageId(eqTo(messageId.value)),
          any[UpdateMessageData],
          any[OffsetDateTime]
        )
    }

    "must return BAD_REQUEST when JSON data extraction fails" in forAll(arbitrary[MovementId], arbitrary[MessageId]) {
      (movementId, messageId) =>
        val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
        val body = Json.obj(
          "status" -> "Nope"
        )
        val request = FakeRequest(
          method = POST,
          uri = routes.MovementsController.updateMessageStatus(movementId, messageId).url,
          headers = headers,
          body = body
        )

        val result =
          controller.updateMessageStatus(movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Could not parse the request"
        )
    }

    "must return NOT_FOUND when an incorrect message is specified" in forAll(arbitrary[MovementId], arbitrary[MessageId], arbitrary[MessageStatus]) {
      (movementId, messageId, messageStatus) =>
        reset(mockRepository) // needed thanks to the generators running the test multiple times.
        val expectedUpdateMessageMetadata = UpdateMessageData(status = messageStatus)

        when(
          mockRepository.updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(expectedUpdateMessageMetadata),
            any[OffsetDateTime]
          )
        )
          .thenReturn(EitherT.leftT(MongoError.DocumentNotFound(s"No movement found with the given id: ${movementId.value}")))

        val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
        val body = Json.obj(
          "status" -> messageStatus.toString
        )
        val request = FakeRequest(
          method = POST,
          uri = routes.MovementsController.updateMessageStatus(movementId, messageId).url,
          headers = headers,
          body = body
        )

        val result =
          controller.updateMessageStatus(movementId, messageId)(request)

        status(result) mustBe NOT_FOUND
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "NOT_FOUND",
          "message" -> s"No movement found with the given id: ${movementId.value}"
        )
    }
  }
}
