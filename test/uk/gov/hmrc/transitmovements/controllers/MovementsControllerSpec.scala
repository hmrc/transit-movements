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

import akka.stream.IOResult
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.util.Timeout
import cats.data.EitherT
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchersSugar.eqTo
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoSugar.reset
import org.mockito.MockitoSugar.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterEach
import org.scalatest.Ignore
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
import uk.gov.hmrc.transitmovements.models._
import uk.gov.hmrc.transitmovements.models.formats.PresentationFormats
import uk.gov.hmrc.transitmovements.models.mongo.UpdateMessageModel
import uk.gov.hmrc.transitmovements.models.requests.UpdateMessageMetadata
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse
import uk.gov.hmrc.transitmovements.repositories.MovementsRepository
import uk.gov.hmrc.transitmovements.services._
import uk.gov.hmrc.transitmovements.services.errors.MessageError
import uk.gov.hmrc.transitmovements.services.errors.MongoError
import uk.gov.hmrc.transitmovements.services.errors.MongoError.UnexpectedError
import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError
import uk.gov.hmrc.transitmovements.services.errors.ParseError

import java.net.URI
import java.security.SecureRandom
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

@nowarn("msg=dead code following this construct|discarded non-Unit value")
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

  val now: OffsetDateTime = OffsetDateTime.now

  lazy val message: Message =
    arbitraryMessage.arbitrary.sample.get.copy(
      id = messageId,
      generated = Some(now),
      received = now,
      triggerId = Some(triggerId),
      uri = Some(new URI("http://www.google.com"))
    )

  lazy val messageFactoryEither: EitherT[Future, MessageError, Message] =
    EitherT.rightT(message)

  def fakeRequest[A](
    method: String,
    body: A,
    messageType: Option[String],
    headers: FakeHeaders = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML))
  ): Request[A] =
    FakeRequest[A](
      method = method,
      uri = routes.MovementsController.updateMovement(movementId, Some(triggerId)).url,
      headers = if (messageType.isDefined) headers.add("X-Message-Type" -> messageType.get) else headers,
      body = body
    )

  val mockMessagesXmlParsingService: MessagesXmlParsingService   = mock[MessagesXmlParsingService]
  val mockMovementsXmlParsingService: MovementsXmlParsingService = mock[MovementsXmlParsingService]
  val mockRepository: MovementsRepository                        = mock[MovementsRepository]
  val mockMessageService: MessageService                         = mock[MessageService]
  val mockMovementFactory: MovementFactory                       = mock[MovementFactory]

  val mockObjectStoreService: ObjectStoreService              = mock[ObjectStoreService]
  implicit val mockTemporaryFileCreator: TemporaryFileCreator = mock[TemporaryFileCreator]
  val mockRandom: SecureRandom                                = mock[SecureRandom]
  val mockAppConfig: AppConfig                                = mock[AppConfig]

  override def beforeEach(): Unit = {
    // TODO: Move this to specific tests as required
    when(mockMessageService.generateId()).thenReturn(messageId)
    super.beforeEach()
  }

  override def afterEach() = {
    reset(mockTemporaryFileCreator)
    reset(mockMovementsXmlParsingService)
    reset(mockMessagesXmlParsingService)
    reset(mockMessageService)
    reset(mockObjectStoreService)
    reset(mockRepository)
    super.afterEach()
  }

  val instant: OffsetDateTime = OffsetDateTime.of(2022, 8, 26, 9, 0, 0, 0, ZoneOffset.UTC)
  implicit val clock: Clock   = Clock.fixed(instant.toInstant, ZoneOffset.UTC)

  val controller =
    new MovementsController(
      stubControllerComponents(),
      mockMessageService,
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

    lazy val declarationData = DeclarationData(eoriNumber, OffsetDateTime.now(ZoneId.of("UTC")))

    lazy val departureDataEither: EitherT[Future, ParseError, DeclarationData] =
      EitherT.rightT(declarationData)

    lazy val messageFactoryEither: EitherT[Future, MessageError, Message] =
      EitherT.rightT(message)

    lazy val received = OffsetDateTime.now(ZoneId.of("UTC"))

    lazy val source: Source[ByteString, _] = Source.single(ByteString("this is test content"))

    lazy val uri = new URI("test")

    lazy val message =
      Message(
        messageId,
        received,
        Some(received),
        Some(MessageType.DeclarationData),
        Some(messageId),
        Some(uri),
        Some("content"),
        Some(MessageStatus.Processing)
      )

    "must return OK if XML data extraction is successful" in {

      val tempFile = SingletonTemporaryFileCreator.create()
      when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

      when(mockMovementsXmlParsingService.extractDeclarationData(any[Source[ByteString, _]]))
        .thenReturn(departureDataEither)

      when(
        mockMovementFactory.createDeparture(
          MovementId(eqTo(movement._id.value)),
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
        mockMessageService.create(
          MovementId(eqTo(movement._id.value)),
          eqTo(MessageType.DeclarationData),
          any[OffsetDateTime],
          any[OffsetDateTime],
          any[Option[MessageId]],
          any[Source[ByteString, Future[_]]],
          any[Long],
          eqTo(MessageStatus.Processing)
        )(any[HeaderCarrier])
      )
        .thenReturn(messageFactoryEither)

      when(mockRepository.insert(any[Movement]))
        .thenReturn(EitherT.rightT(Right(())))

      val request = fakeRequest(POST, validXml, Some(MessageType.DeclarationData.code))
      val result: Future[Result] =
        controller.createMovement(eoriNumber, MovementType.Departure)(request =
          request.map(
            n => Source.single(ByteString(n.mkString))
          )
        )

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj(
        "movementId" -> movementId.value,
        "messageId"  -> messageId.value
      )

      verify(mockMessageService, times(1)).create(
        MovementId(eqTo(movement._id.value)),
        eqTo(MessageType.DeclarationData),
        any[OffsetDateTime],
        any[OffsetDateTime],
        any[Option[MessageId]],
        any[Source[ByteString, Future[IOResult]]],
        any[Long],
        eqTo(MessageStatus.Processing)
      )(any[HeaderCarrier])
    }

    "must return OK if XML data extraction is successful and is stored in object store" in {

      val tempFile = SingletonTemporaryFileCreator.create()
      when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

      when(mockMovementsXmlParsingService.extractDeclarationData(eqTo(source)))
        .thenReturn(departureDataEither)

      val messageToSend  = message.copy(body = None)
      val movementToSend = movement.copy(messages = Vector(messageToSend))

      when(
        mockMovementFactory.createDeparture(
          MovementId(eqTo(movementId.value)),
          any[String].asInstanceOf[EORINumber],
          eqTo(MovementType.Departure),
          eqTo(declarationData),
          eqTo(messageToSend),
          any[OffsetDateTime],
          any[OffsetDateTime]
        )
      )
        .thenReturn(movement)

      when(
        mockMessageService.create(
          MovementId(eqTo(movementId.value)),
          eqTo(MessageType.DeclarationData),
          any[OffsetDateTime],
          any[OffsetDateTime],
          any[Option[MessageId]],
          any[Source[ByteString, _]],
          any[Long],
          eqTo(MessageStatus.Processing)
        )(any())
      ).thenReturn(EitherT.rightT(messageToSend))

      when(mockRepository.insert(eqTo(movementToSend)))
        .thenReturn(EitherT.rightT(Right(())))

      val request = fakeRequest(POST, validXml, Some(MessageType.DeclarationData.code))

      val result =
        controller.createMovement(eoriNumber, MovementType.Departure)(request)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj(
        "movementId" -> movementId.value,
        "messageId"  -> messageId.value
      )
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

        val request = fakeRequest(POST, Source.single(ByteString("test string")), Some(MessageType.DeclarationData.code))

        val result: Future[Result] =
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

    lazy val arrivalData = ArrivalData(eoriNumber, OffsetDateTime.now(ZoneId.of("UTC")), mrn)

    lazy val arrivalDataEither: EitherT[Future, ParseError, ArrivalData] =
      EitherT.rightT(arrivalData)

    lazy val received = OffsetDateTime.now(ZoneId.of("UTC"))

    lazy val source: Source[ByteString, _] = Source.single(ByteString("this is test content"))

    lazy val message =
      Message(
        messageId,
        received,
        Some(received),
        Some(MessageType.ArrivalNotification),
        Some(messageId),
        None,
        Some("content"),
        Some(MessageStatus.Processing)
      )

    "must return OK if XML data extraction is successful" in {

      val tempFile = SingletonTemporaryFileCreator.create()
      when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

      when(mockMovementsXmlParsingService.extractArrivalData(any[Source[ByteString, _]]))
        .thenReturn(arrivalDataEither)

      val movementToSend = movement.copy(messages = Vector(message))

      when(
        mockMovementFactory.createArrival(
          MovementId(eqTo(movementId.value)),
          EORINumber(eqTo(eoriNumber.value)),
          eqTo(MovementType.Arrival),
          eqTo(arrivalData),
          eqTo(message),
          any[OffsetDateTime],
          any[OffsetDateTime]
        )
      )
        .thenReturn(movementToSend)

      when(
        mockMessageService.create(
          MovementId(eqTo(movementId.value)),
          eqTo(MessageType.ArrivalNotification),
          any[OffsetDateTime],
          any[OffsetDateTime],
          eqTo(message.triggerId),
          any[Source[ByteString, Future[IOResult]]],
          any[Long],
          eqTo(MessageStatus.Processing)
        )(any[HeaderCarrier])
      )
        .thenReturn(EitherT.rightT(message))

      when(mockRepository.insert(any()))
        .thenReturn(EitherT.rightT(Right(())))

      val request = fakeRequest(POST, validXml, Some(MessageType.ArrivalNotification.code))

      val result: Future[Result] =
        controller.createMovement(eoriNumber, MovementType.Arrival)(
          request.map(
            x => Source.single[ByteString](ByteString(x.mkString))
          )
        )

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj(
        "movementId" -> movementId.value,
        "messageId"  -> messageId.value
      )

      verify(mockMessageService, times(1)).create(
        MovementId(eqTo(movementId.value)),
        eqTo(MessageType.ArrivalNotification),
        any(),
        any(),
        any(),
        any(),
        any[Long],
        eqTo(MessageStatus.Processing)
      )(any[HeaderCarrier])
    }

    "must return OK if XML data extraction is successful and store in object store when the limit exceeds" in {

      val tempFile = SingletonTemporaryFileCreator.create()
      when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

      when(mockMovementsXmlParsingService.extractArrivalData(eqTo(source)))
        .thenReturn(arrivalDataEither)

      val messageToSend  = message.copy(body = None, uri = Some(new URI(testObjectStoreURI(movementId, messageId, now).value)))
      val movementToSend = movement.copy(messages = Vector(messageToSend))

      when(
        mockMovementFactory.createArrival(
          MovementId(eqTo(movementId.value)),
          EORINumber(eqTo(eoriNumber.value)),
          eqTo(MovementType.Arrival),
          eqTo(arrivalData),
          eqTo(message),
          any[OffsetDateTime],
          any[OffsetDateTime]
        )
      )
        .thenReturn(movementToSend)

      when(
        mockMessageService.create(
          MovementId(eqTo(movementId.value)),
          eqTo(MessageType.ArrivalNotification),
          any[OffsetDateTime],
          any[OffsetDateTime],
          eqTo(message.triggerId),
          any[Source[ByteString, Future[IOResult]]],
          any[Long],
          eqTo(MessageStatus.Processing)
        )(any[HeaderCarrier])
      )
        .thenReturn(EitherT.rightT(messageToSend))

      when(mockRepository.insert(any()))
        .thenReturn(EitherT.rightT(Right(())))

      val request = fakeRequest(POST, validXml, Some(MessageType.ArrivalNotification.code))

      val result =
        controller.createMovement(eoriNumber, MovementType.Arrival)(request)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj(
        "movementId" -> movementId.value,
        "messageId"  -> messageId.value
      )
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

        val request = fakeRequest(POST, Source.single(ByteString(validXml.mkString)), Some(MessageType.ArrivalNotification.code))

        val result: Future[Result] =
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
            .thenReturn(EitherT.rightT(Some(MovementWithoutMessages.fromMovement(movement))))

          val result = controller.getMovementWithoutMessages(eoriNumber, movementType, movementId)(request)

          status(result) mustBe OK
          contentAsJson(result) mustBe Json.toJson(MovementWithoutMessages.fromMovement(movement))(PresentationFormats.movementWithoutMessagesFormat)
        }

        "must return NOT_FOUND if no departure found" in {
          when(mockRepository.getMovementWithoutMessages(EORINumber(any()), MovementId(any()), eqTo(movementType)))
            .thenReturn(EitherT.rightT(None))

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
            .thenReturn(EitherT.rightT(Some(messageResponse)))

          val result = controller.getMessage(eoriNumber, movementType, movementId, messageId)(request)

          status(result) mustBe OK
          contentAsJson(result) mustBe Json.toJson(messageResponse)
        }

        "must return OK along with uri if message found in the correct format" in {
          val messageResponse = MessageResponse.fromMessageWithoutBody(movement.messages.head)

          when(mockRepository.getSingleMessage(EORINumber(any()), MovementId(any()), MessageId(any()), eqTo(movementType)))
            .thenReturn(EitherT.rightT(Some(messageResponse)))

          val result = controller.getMessage(eoriNumber, movementType, movementId, messageId)(request)

          status(result) mustBe OK
          contentAsJson(result) mustBe Json.toJson(messageResponse)
        }

        "must return NOT_FOUND if no message found" in {
          when(mockRepository.getSingleMessage(EORINumber(any()), MovementId(any()), MessageId(any()), eqTo(movementType)))
            .thenReturn(EitherT.rightT(None))

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

          when(mockRepository.getMovements(EORINumber(any()), eqTo(movementType), eqTo(None), eqTo(None)))
            .thenReturn(EitherT.rightT(Vector(response)))

          val result = controller.getMovementsForEori(eoriNumber, movementType)(request)
          status(result) mustBe OK
          contentAsJson(result) mustBe Json.toJson(Vector(response))
        }

        "must return OK if departures were found and it match the updatedSince or movementEORI or both filter" in forAll(
          Gen.option(arbitrary[OffsetDateTime]),
          Gen.option(arbitrary[EORINumber])
        ) {
          (updatedSince, movementEORI) =>
            val response = MovementWithoutMessages.fromMovement(movement)

            when(mockRepository.getMovements(EORINumber(any()), eqTo(movementType), eqTo(updatedSince), eqTo(movementEORI)))
              .thenReturn(EitherT.rightT(Vector(response)))

            val result = controller.getMovementsForEori(eoriNumber, movementType, updatedSince, movementEORI)(request)

            status(result) mustBe OK
            contentAsJson(result) mustBe Json.toJson(Vector(response))
        }

        "must return NOT_FOUND if no ids were found" in forAll(Gen.option(arbitrary[OffsetDateTime]), Gen.option(arbitrary[EORINumber])) {
          (updatedSince, movementEORI) =>
            when(mockRepository.getMovements(EORINumber(any()), eqTo(movementType), eqTo(updatedSince), eqTo(movementEORI)))
              .thenReturn(EitherT.rightT(Vector.empty[MovementWithoutMessages]))

            val result = controller.getMovementsForEori(eoriNumber, movementType, updatedSince, movementEORI)(request)

            status(result) mustBe NOT_FOUND
        }

        "must return INTERNAL_SERVICE_ERROR when a database error is thrown" in forAll(
          Gen.option(arbitrary[OffsetDateTime]),
          Gen.option(arbitrary[EORINumber])
        ) {
          (updatedSince, movementEORI) =>
            when(mockRepository.getMovements(EORINumber(any()), any(), eqTo(updatedSince), eqTo(movementEORI)))
              .thenReturn(EitherT.leftT(MongoError.UnexpectedError(Some(new Throwable("test")))))

            val result = controller.getMovementsForEori(eoriNumber, movementType, updatedSince, movementEORI)(request)

            status(result) mustBe INTERNAL_SERVER_ERROR
        }
      }
    }
  }

  "updateMovement" - {

    lazy val messageData: MessageData = MessageData(OffsetDateTime.now(ZoneId.of("UTC")), None)

    lazy val messageDataEither: EitherT[Future, ParseError, MessageData] =
      EitherT.rightT(messageData)

    val validXml: NodeSeq =
      <CC015C>
        <messageSender>ABC123</messageSender>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
      </CC015C>

    lazy val messageFactoryEither: EitherT[Future, MessageError, Message] =
      EitherT.rightT(message)

    lazy val objectSummaryEither: EitherT[Future, ObjectStoreError, ObjectSummaryWithMd5] = EitherT.rightT(objectSummary)

    "for small messages, must return OK if XML data extraction is successful" in forAll(Gen.oneOf(MessageType.requestValues)) {
      messageType =>
        val tempFile = SingletonTemporaryFileCreator.create()
        when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

        when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], any[MessageType]))
          .thenReturn(messageDataEither)

        when(
          mockMessageService.create(
            MovementId(eqTo(movementId.value)),
            eqTo(messageType),
            any[OffsetDateTime],
            any[OffsetDateTime],
            eqTo(Some(triggerId)),
            any[Source[ByteString, Future[IOResult]]],
            any[Long],
            eqTo(MessageStatus.Processing)
          )(any[HeaderCarrier])
        )
          .thenReturn(messageFactoryEither)

        when(mockRepository.addMessage(any[String].asInstanceOf[MovementId], any[Message], any[Option[MovementReferenceNumber]], any[OffsetDateTime]))
          .thenReturn(EitherT.rightT(()))

        val request = fakeRequest(POST, validXml, Some(messageType.code))

        val result =
          controller.updateMovement(movementId, Some(triggerId))(request)

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.obj("messageId" -> messageId.value)
    }

    "for small messages, must return OK if XML data extraction is successful and is stored in Object store for a trader sent message" in forAll(
      arbitrary[Message],
      Gen.oneOf(MessageType.departureRequestValues)
    ) {
      (arbMessage, messageType) =>
        val tempFile = SingletonTemporaryFileCreator.create()

        val message =
          arbMessage.copy(
            id = messageId,
            generated = Some(now),
            received = now,
            triggerId = Some(triggerId),
            uri = Some(new URI(objectSummary.location.asUri)),
            messageType = Some(messageType)
          )

        when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

        when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], any[MessageType]))
          .thenReturn(EitherT.rightT(MessageData(now, None)))

        when(
          mockObjectStoreService.putObjectStoreFile(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), any[Source[ByteString, _]])(
            any(),
            any()
          )
        ).thenReturn(objectSummaryEither)

        when(
          mockMessageService.create(
            MovementId(eqTo(movementId.value)),
            eqTo(messageType),
            any[OffsetDateTime],
            any[OffsetDateTime],
            eqTo(Some(triggerId)),
            any[Source[ByteString, _]],
            any[Long],
            eqTo(MessageStatus.Processing)
          )(any[HeaderCarrier])
        )
          .thenReturn(EitherT.rightT(message))

        when(mockRepository.addMessage(MovementId(eqTo(movementId.value)), eqTo(message), eqTo(None), any[OffsetDateTime]))
          .thenReturn(EitherT.rightT(()))

        val request = fakeRequest(POST, validXml, Some(messageType.code))

        val result =
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

        val tempFile = SingletonTemporaryFileCreator.create()
        when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

        when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], any[MessageType]))
          .thenReturn(
            EitherT.leftT(
              ParseError.BadDateTime("preparationDateAndTime", new DateTimeParseException("Text 'invalid' could not be parsed at index 0", cs, 0))
            )
          )

        val request = fakeRequest(POST, xml, Some("IE009"))

        val result =
          controller.updateMovement(movementId, Some(triggerId))(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Could not parse datetime for preparationDateAndTime: Text 'invalid' could not be parsed at index 0"
        )
      }

      "contains message to indicate update failed due to document with given id not found" in forAll(Gen.oneOf(MessageType.requestValues)) {
        messageType =>
          val tempFile = SingletonTemporaryFileCreator.create()
          when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

          when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], eqTo(messageType)))
            .thenReturn(messageDataEither)

          when(
            mockMessageService.create(
              MovementId(eqTo(movementId.value)),
              eqTo(messageType),
              any[OffsetDateTime],
              any[OffsetDateTime],
              eqTo(Some(triggerId)),
              any[Source[ByteString, _]],
              any[Long],
              eqTo(MessageStatus.Processing)
            )(any[HeaderCarrier])
          )
            .thenReturn(messageFactoryEither)

          when(mockRepository.addMessage(any[String].asInstanceOf[MovementId], any[Message], any[Option[MovementReferenceNumber]], any[OffsetDateTime]))
            .thenReturn(EitherT.leftT(MongoError.DocumentNotFound(s"No departure found with the given id: ${movementId.value}")))

          val request = fakeRequest(POST, validXml, Some(messageType.code))

          val result =
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

      "when an invalid xml causes an unknown ParseError to be thrown" in forAll(arbitrary[MessageType]) {
        messageType =>
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

      // ignored for now -- will revisit if/when this accepts an Upscan URL instead of an object store one
      "when file creation fails" ignore forAll(arbitrary[MessageType]) {
        messageType =>
          when(mockTemporaryFileCreator.create()).thenThrow(new Exception("File creation failed"))

          val request =
            fakeRequest(
              POST,
              Source.single(ByteString(validXml.mkString)),
              Some(messageType.code),
              headers = FakeHeaders(Seq("X-Object-Store-Uri" -> "common-transit-convention-traders/abc.xml"))
            )

          val result: Future[Result] =
            controller.updateMovement(movementId, Some(triggerId))(request)

          result.recoverWith {
            case NonFatal(e) =>
              e.printStackTrace()
              result
          }

          status(result) mustBe INTERNAL_SERVER_ERROR
          contentAsJson(result) mustBe Json.obj(
            "code"    -> "INTERNAL_SERVER_ERROR",
            "message" -> "Internal server error"
          )
      }
    }
  }

  "updateMovement for Large message" - {

    val messageType = MessageType.DeclarationData

    lazy val messageData: MessageData = MessageData(OffsetDateTime.now(ZoneId.of("UTC")), None)

    lazy val messageDataEither: EitherT[Future, ParseError, MessageData] =
      EitherT.rightT(messageData)

    lazy val messageFactory =
      arbitraryMessage.arbitrary.sample.get.copy(id = messageId, generated = Some(now), received = now, triggerId = Some(triggerId), uri = Some(objectStoreURI))

    "must return OK if XML data extraction is successful" in forAll(arbitrary[ObjectStoreURI]) {
      objectStoreURI =>
        val objectStoreResourceLocation = objectStoreURI.asResourceLocation

        when(
          mockObjectStoreService
            .getObjectStoreFile(ObjectStoreResourceLocation(eqTo(objectStoreResourceLocation.value.value)))(any[ExecutionContext], any[HeaderCarrier])
        )
          .thenReturn(EitherT.rightT(Source.single(ByteString("this is test content"))))

        when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, _]], any[MessageType]))
          .thenReturn(messageDataEither)

        when(
          mockMessageService.createWithURI(
            eqTo(messageType),
            any[OffsetDateTime],
            any[OffsetDateTime],
            eqTo(Some(triggerId)),
            ObjectStoreURI(eqTo(objectStoreURI.value)),
            eqTo(MessageStatus.Received)
          )
        )
          .thenReturn(message)

        when(mockRepository.addMessage(MovementId(eqTo(movementId.value)), eqTo(message), eqTo(messageData.mrn), any[OffsetDateTime]))
          .thenReturn(EitherT.rightT(()))

        lazy val request = FakeRequest(
          method = "POST",
          uri = routes.MovementsController.updateMovement(movementId, Some(triggerId)).url,
          headers = FakeHeaders(Seq("X-Message-Type" -> messageType.code, "X-Object-Store-Uri" -> objectStoreURI.value)),
          body = Source.empty[ByteString]
        )

        val result: Future[Result] =
          controller.updateMovement(movementId, Some(triggerId))(request)

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.obj("messageId" -> messageId.value)
    }

    "must return BAD_REQUEST when Object Store uri header not supplied" in {

      lazy val request = FakeRequest(
        method = "POST",
        uri = routes.MovementsController.updateMovement(movementId, Some(triggerId)).url,
        headers = FakeHeaders(Seq("X-Message-Type" -> messageType.code)),
        body = AnyContentAsEmpty
      )

      val result =
        controller.updateMovement(movementId, Some(triggerId))(request)

      status(result) mustBe BAD_REQUEST
      contentAsJson(result) mustBe Json.obj(
        "code"    -> "BAD_REQUEST",
        "message" -> "Missing X-Object-Store-Uri header value"
      )
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

  "updateMessage (for PATCH)" - {

    "must return OK if successful given both an object store URI and status" - {

      "if the message is a departure message and the first one in the movement" in forAll(
        arbitrary[EORINumber],
        arbitrary[MovementId],
        arbitrary[MessageId],
        arbitrary[ObjectStoreURI]
      ) {
        (eori, movementId, messageId, objectStoreURI) =>
          when(
            mockRepository.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(MovementType.Departure))
          )
            .thenReturn(EitherT.rightT(Some(MovementWithoutMessages(movementId, eori, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock)))))

          when(
            mockObjectStoreService
              .getObjectStoreFile(ObjectStoreResourceLocation(eqTo(objectStoreURI.asResourceLocation.value.value)))(any[ExecutionContext], any[HeaderCarrier])
          )
            .thenReturn(EitherT.rightT(Source.empty[ByteString]))

          when(
            mockMovementsXmlParsingService.extractData(eqTo(MovementType.Departure), any[Source[ByteString, _]])
          )
            .thenReturn(EitherT.rightT(DeclarationData(eori, OffsetDateTime.now(clock))))

          val expectedUpdateMessageModel = UpdateMessageModel(
            Some(objectStoreURI),
            None,
            MessageStatus.Success
          )

          when(
            mockRepository.updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              eqTo(expectedUpdateMessageModel),
              any[OffsetDateTime]
            )
          )
            .thenReturn(EitherT.rightT(()))

          when(
            mockRepository.updateMovementMetadata(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(None), any[OffsetDateTime])
          )
            .thenReturn(EitherT.rightT(()))

          val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
          val body = Json.obj(
            "objectStoreURI" -> objectStoreURI.value,
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
            eqTo(expectedUpdateMessageModel),
            any[OffsetDateTime]
          )
          verify(mockRepository, times(1)).updateMovementMetadata(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(None), any[OffsetDateTime])
      }

      "if the message is an arrival message and the first one in the movement" in forAll(
        arbitrary[EORINumber],
        arbitrary[MovementId],
        arbitrary[MessageId],
        arbitrary[MovementReferenceNumber],
        arbitrary[ObjectStoreURI]
      ) {
        (eori, movementId, messageId, mrn, objectStoreURI) =>
          when(
            mockRepository.getMovementWithoutMessages(eqTo(eori), MovementId(eqTo(movementId.value)), eqTo(MovementType.Arrival))
          )
            .thenReturn(EitherT.rightT(Some(MovementWithoutMessages(movementId, eori, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock)))))

          when(
            mockObjectStoreService
              .getObjectStoreFile(ObjectStoreResourceLocation(eqTo(objectStoreURI.asResourceLocation.value.value)))(any[ExecutionContext], any[HeaderCarrier])
          )
            .thenReturn(EitherT.rightT(Source.empty[ByteString]))

          when(
            mockMovementsXmlParsingService.extractData(eqTo(MovementType.Arrival), any[Source[ByteString, _]])
          )
            .thenReturn(EitherT.rightT(ArrivalData(eori, OffsetDateTime.now(clock), mrn)))

          val expectedUpdateMessageModel = UpdateMessageModel(
            Some(objectStoreURI),
            None,
            MessageStatus.Success
          )

          when(
            mockRepository.updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              eqTo(expectedUpdateMessageModel),
              any[OffsetDateTime]
            )
          )
            .thenReturn(EitherT.rightT(()))

          when(
            mockRepository.updateMovementMetadata(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(Some(mrn)), any[OffsetDateTime])
          )
            .thenReturn(EitherT.rightT(()))

          val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
          val body = Json.obj(
            "objectStoreURI" -> objectStoreURI.value,
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
            eqTo(expectedUpdateMessageModel),
            any[OffsetDateTime]
          )
          verify(mockRepository, times(1)).updateMovementMetadata(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(Some(mrn)), any[OffsetDateTime])
      }

      "if the movement EORI has already been set" in forAll(
        arbitrary[EORINumber],
        arbitrary[MovementId],
        arbitrary[MessageId],
        arbitrary[MovementType],
        arbitrary[ObjectStoreURI]
      ) {
        (eori, movementId, messageId, movementType, objectStoreURI) =>
          when(
            mockRepository.getMovementWithoutMessages(eqTo(eori), MovementId(eqTo(movementId.value)), eqTo(movementType))
          )
            .thenReturn(
              EitherT.rightT(Some(MovementWithoutMessages(movementId, eori, Some(eori), None, OffsetDateTime.now(clock), OffsetDateTime.now(clock))))
            )

          val expectedUpdateMessageModel = UpdateMessageModel(
            Some(objectStoreURI),
            None,
            MessageStatus.Success
          )

          when(
            mockRepository.updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              eqTo(expectedUpdateMessageModel),
              any[OffsetDateTime]
            )
          )
            .thenReturn(EitherT.rightT(()))

          when(
            mockRepository.updateMovementMetadata(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(None), any[OffsetDateTime])
          )
            .thenReturn(EitherT.rightT(()))

          val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
          val body = Json.obj(
            "objectStoreURI" -> objectStoreURI.value,
            "status"         -> "Success"
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
            eqTo(expectedUpdateMessageModel),
            any[OffsetDateTime]
          )
          verify(mockRepository, times(0)).updateMovementMetadata(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(None), any[OffsetDateTime])
      }
    }

    "must return Bad Request" - {

      "if the object store URI is not a common-transit-convention-traders owned URI" in forAll(
        arbitrary[EORINumber],
        arbitrary[MovementId],
        arbitrary[MessageId]
      ) {
        (eori, movementId, messageId) =>
          val objectStoreURI = ObjectStoreURI("not-owned/a.xml")

          when(
            mockRepository.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(MovementType.Departure))
          )
            .thenReturn(EitherT.rightT(Some(MovementWithoutMessages(movementId, eori, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock)))))

          val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
          val body = Json.obj(
            "objectStoreURI" -> "not-owned/a.xml",
            "status"         -> "Success"
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
            "message" -> "Provided Object Store URI is not owned by common-transit-convention-traders"
          )
          verify(mockRepository, times(0)).updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            any[UpdateMessageModel],
            any[OffsetDateTime]
          )
          verify(mockRepository, times(0)).updateMovementMetadata(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(None), any[OffsetDateTime])
      }
    }

    "must return OK, if the update message is successful, given only status is provided in the request" in forAll(
      arbitrary[EORINumber],
      arbitrary[MovementType],
      arbitrary[MessageStatus]
    ) {
      (eori, movementType, messageStatus) =>
        reset(mockRepository) // needed thanks to the generators running the test multiple times.
        val expectedUpdateMessageModel = UpdateMessageModel(None, None, messageStatus)

        when(
          mockRepository.getMovementWithoutMessages(eqTo(eori), MovementId(eqTo(movementId.value)), eqTo(movementType))
        )
          .thenReturn(
            EitherT.rightT(Some(MovementWithoutMessages(movementId, eori, Some(eori), None, OffsetDateTime.now(clock), OffsetDateTime.now(clock))))
          )

        when(
          mockRepository.updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(expectedUpdateMessageModel),
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
          eqTo(expectedUpdateMessageModel),
          any[OffsetDateTime]
        )
        verify(mockRepository, times(0)).updateMovementMetadata(MovementId(eqTo(movementId.value)), eqTo(Some(eori)), eqTo(None), any[OffsetDateTime])
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
            any[UpdateMessageModel],
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

  }
}
