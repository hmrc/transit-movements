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

import cats.data.EitherT
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq as eqTo
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterEach
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
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
import play.api.mvc.DefaultActionBuilder
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
import uk.gov.hmrc.internalauth.client.*
import uk.gov.hmrc.transitmovements.base.SpecBase
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.config.AppConfig
import uk.gov.hmrc.transitmovements.config.Constants
import uk.gov.hmrc.transitmovements.controllers.actions.ClientIdRefiner
import uk.gov.hmrc.transitmovements.controllers.actions.InternalAuthActionProvider
import uk.gov.hmrc.transitmovements.controllers.actions.ValidateAcceptRefiner
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.matchers.UpdateMessageDataMatcher
import uk.gov.hmrc.transitmovements.models.*
import uk.gov.hmrc.transitmovements.models.APIVersionHeader.V2_1
import uk.gov.hmrc.transitmovements.models.APIVersionHeader.V3_0
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse
import uk.gov.hmrc.transitmovements.models.responses.UpdateMovementResponse
import uk.gov.hmrc.transitmovements.services.*
import uk.gov.hmrc.transitmovements.services.errors.MongoError.UnexpectedError
import uk.gov.hmrc.transitmovements.services.errors.MongoError
import uk.gov.hmrc.transitmovements.services.errors.ParseError
import uk.gov.hmrc.transitmovements.services.errors.StreamError

import java.net.URI
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.xml.NodeSeq

class MovementsControllerSpec
    extends SpecBase
    with TestActorSystem
    with Matchers
    with OptionValues
    with MockitoSugar
    with ScalaFutures
    with BeforeAndAfterEach
    with ModelGenerators
    with ScalaCheckDrivenPropertyChecks {

  implicit val timeout: Timeout = 5.seconds

  val mockMessagesXmlParsingService: MessagesXmlParsingService   = mock[MessagesXmlParsingService]
  val mockMovementsXmlParsingService: MovementsXmlParsingService = mock[MovementsXmlParsingService]
  val mockPersistenceService: PersistenceService                 = mock[PersistenceService]
  val mockMessageFactory: MessageService                         = mock[MessageService]
  val mockMovementFactory: MovementFactory                       = mock[MovementFactory]

  val mockObjectStoreService: ObjectStoreService              = mock[ObjectStoreService]
  implicit val mockTemporaryFileCreator: TemporaryFileCreator = mock[TemporaryFileCreator]
  val mockAppConfig: AppConfig                                = mock[AppConfig]

  val mockInternalAuthActionProvider: InternalAuthActionProvider = mock[InternalAuthActionProvider]

  val movementId        = arbitraryMovementId.arbitrary.sample.get
  val messageId         = arbitraryMessageId.arbitrary.sample.get
  val previousMessageId = arbitraryMessageId.arbitrary.sample.get
  val triggerId         = arbitraryMessageId.arbitrary.sample.get

  when(mockMessageFactory.generateId()).thenReturn(messageId.value)
  when(mockMovementFactory.generateId()).thenReturn(movementId.value)

  private def resetInternalAuth() = {
    reset(mockInternalAuthActionProvider)
    // any here, verify specifics later.
    when(mockInternalAuthActionProvider.apply(any())(any())).thenReturn(DefaultActionBuilder(stubControllerComponents().parsers.defaultBodyParser))
  }

  override def beforeEach(): Unit = {
    resetInternalAuth()
    super.beforeEach()
  }

  override def afterEach(): Unit = {
    reset(mockTemporaryFileCreator)
    reset(mockMovementsXmlParsingService)
    reset(mockMessagesXmlParsingService)
    reset(mockPersistenceService)
    reset(mockMessageFactory)
    reset(mockObjectStoreService)
    super.afterEach()
  }

  val instant: OffsetDateTime         = OffsetDateTime.of(2022, 8, 26, 9, 0, 0, 0, ZoneOffset.UTC)
  implicit val clock: Clock           = Clock.fixed(instant.toInstant, ZoneOffset.UTC)
  private val sourceManagementService = new SourceManagementServiceImpl()
  private val clientIdRefiner         = new ClientIdRefiner
  private val validateAcceptRefiner   = new ValidateAcceptRefiner(stubControllerComponents())

  val controller =
    new MovementsController(
      stubControllerComponents(),
      mockMessageFactory,
      mockMovementFactory,
      mockPersistenceService,
      mockMovementsXmlParsingService,
      mockMessagesXmlParsingService,
      sourceManagementService,
      mockObjectStoreService,
      mockInternalAuthActionProvider,
      validateAcceptRefiner,
      clientIdRefiner
    )

  def fakeRequest[A](
    method: String,
    body: A,
    movementId: MovementId,
    triggerId: Option[MessageId],
    messageType: Option[String],
    headers: FakeHeaders = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML, Constants.APIVersionHeaderKey -> APIVersionHeader.V2_1.value))
  ): Request[A] =
    FakeRequest(
      method = method,
      uri = routes.MovementsController.updateMovement(movementId, triggerId).url,
      headers = if (messageType.isDefined) headers.add("X-Message-Type" -> messageType.get) else headers,
      body = body
    )

  "createMovement - Departure" - {
    "must return OK if XML data extraction is successful and mark the movement as '3.0' with 3.0 header" in {

      val validXml: NodeSeq =
        <CC015C>
          <messageSender>ABC123</messageSender>
          <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
        </CC015C>

      val validXmlStream: Source[ByteString, ?] = Source.single(ByteString(validXml.mkString))
      val eoriNumber: EORINumber                = arbitrary[EORINumber].sample.get
      val lrn: LocalReferenceNumber             = arbitraryLRN.arbitrary.sample.get
      val messageSender: MessageSender          = arbitraryMessageSender.arbitrary.sample.get
      val declarationData                       = DeclarationData(Some(eoriNumber), OffsetDateTime.now(ZoneId.of("UTC")), lrn, messageSender)

      val departureDataEither: EitherT[Future, ParseError, DeclarationData] = EitherT.rightT(declarationData)

      val received             = OffsetDateTime.now(ZoneId.of("UTC"))
      val uri                  = new URI("test")
      val size                 = Gen.chooseNum[Long](1L, 25000L).sample.getOrElse(1L) // <- This is safer, as it handles None case
      val messageId: MessageId = MessageId("0123456789abcdef")
      val message              =
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

      val messageFactoryEither: EitherT[Future, StreamError, Message] =
        EitherT.rightT(message)

      val tempFile = SingletonTemporaryFileCreator.create()

      val now: OffsetDateTime = OffsetDateTime.now

      val movement: Movement = arbitrary[Movement].sample.value.copy(
        _id = movementId,
        enrollmentEORINumber = eoriNumber,
        movementEORINumber = Some(eoriNumber),
        created = now,
        updated = now,
        messages = Vector(message),
        apiVersion = V3_0
      )

      when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

      when(mockMovementsXmlParsingService.extractDeclarationData(any[Source[ByteString, ?]]))
        .thenReturn(departureDataEither)

      when(
        mockMovementFactory.createDeparture(
          any[String].asInstanceOf[MovementId],
          any[String].asInstanceOf[EORINumber],
          any[String].asInstanceOf[MovementType],
          any[DeclarationData],
          any[Message],
          any[OffsetDateTime],
          any[OffsetDateTime],
          Some(any[String].asInstanceOf[ClientId]),
          eqTo(V3_0)
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
          any[Source[ByteString, ?]],
          eqTo(MessageStatus.Processing)
        )(any[HeaderCarrier])
      )
        .thenReturn(messageFactoryEither)

      when(mockPersistenceService.insertMovement(eqTo(movement)))
        .thenReturn(EitherT.liftF(Future.unit))
      val request: Request[Source[ByteString, ?]] =
        fakeRequest[Source[ByteString, ?]](
          POST,
          validXmlStream,
          movementId,
          Some(triggerId),
          Some(MessageType.DeclarationData.code),
          FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML, Constants.APIVersionHeaderKey -> APIVersionHeader.V3_0.value))
        )
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
        any[Source[ByteString, ?]],
        eqTo(MessageStatus.Processing)
      )(any[HeaderCarrier])

      verify(mockInternalAuthActionProvider, times(1)).apply(
        eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("WRITE")))
      )(any[ExecutionContext])
      verifyNoMoreInteractions(mockInternalAuthActionProvider)
    }
    "must return OK if XML data extraction is successful and mark the movement as '2.1' with 2.1 header" in {

      val validXml: NodeSeq =
        <CC015C>
          <messageSender>ABC123</messageSender>
          <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
        </CC015C>

      val validXmlStream: Source[ByteString, ?] = Source.single(ByteString(validXml.mkString))
      val eoriNumber: EORINumber                = arbitrary[EORINumber].sample.get
      val lrn: LocalReferenceNumber             = arbitraryLRN.arbitrary.sample.get
      val messageSender: MessageSender          = arbitraryMessageSender.arbitrary.sample.get
      val declarationData                       = DeclarationData(Some(eoriNumber), OffsetDateTime.now(ZoneId.of("UTC")), lrn, messageSender)

      val departureDataEither: EitherT[Future, ParseError, DeclarationData] =
        EitherT.rightT(declarationData)

      val received             = OffsetDateTime.now(ZoneId.of("UTC"))
      val uri                  = new URI("test")
      val size                 = Gen.chooseNum[Long](1L, 25000L).sample.getOrElse(1L) // <- This is safer, as it handles None case
      val messageId: MessageId = MessageId("0123456789abcdef")
      val message              =
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

      val messageFactoryEither: EitherT[Future, StreamError, Message] =
        EitherT.rightT(message)

      val tempFile = SingletonTemporaryFileCreator.create()

      val now: OffsetDateTime = OffsetDateTime.now

      val movement: Movement = arbitrary[Movement].sample.value.copy(
        _id = movementId,
        enrollmentEORINumber = eoriNumber,
        movementEORINumber = Some(eoriNumber),
        created = now,
        updated = now,
        messages = Vector(message),
        apiVersion = V2_1
      )

      when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

      when(mockMovementsXmlParsingService.extractDeclarationData(any[Source[ByteString, ?]]))
        .thenReturn(departureDataEither)

      when(
        mockMovementFactory.createDeparture(
          any[String].asInstanceOf[MovementId],
          any[String].asInstanceOf[EORINumber],
          any[String].asInstanceOf[MovementType],
          any[DeclarationData],
          any[Message],
          any[OffsetDateTime],
          any[OffsetDateTime],
          Some(any[String].asInstanceOf[ClientId]),
          eqTo(V2_1)
        )
      )
        .thenReturn(movement)

      when(
        mockMessageFactory.create(
          any,
          eqTo(MessageType.DeclarationData),
          any[OffsetDateTime],
          any[OffsetDateTime],
          any[Option[MessageId]],
          any[Long],
          any[Source[ByteString, ?]],
          eqTo(MessageStatus.Processing)
        )(any[HeaderCarrier])
      )
        .thenReturn(messageFactoryEither)

      when(mockPersistenceService.insertMovement(eqTo(movement))).thenReturn(EitherT.liftF(Future.unit))
      val request: Request[Source[ByteString, ?]] =
        fakeRequest[Source[ByteString, ?]](POST, validXmlStream, movementId, Some(triggerId), Some(MessageType.DeclarationData.code))
      val result: Future[Result] =
        controller.createMovement(eoriNumber, MovementType.Departure)(request)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj(
        "movementId" -> movementId.value,
        "messageId"  -> messageId.value
      )

      verify(mockMessageFactory, times(1)).create(
        any,
        eqTo(MessageType.DeclarationData),
        any[OffsetDateTime],
        any[OffsetDateTime],
        any[Option[MessageId]],
        any[Long],
        any[Source[ByteString, ?]],
        eqTo(MessageStatus.Processing)
      )(any[HeaderCarrier])

      verify(mockInternalAuthActionProvider, times(1)).apply(
        eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("WRITE")))
      )(any[ExecutionContext])
      verifyNoMoreInteractions(mockInternalAuthActionProvider)
    }

    "must return BAD_REQUEST when XML data extraction fails" - {

      "contains message to indicate element not found" in {

        val elementNotFoundXml: NodeSeq =
          <CC015C></CC015C>

        val eoriNumber: EORINumber = arbitrary[EORINumber].sample.get

        when(mockMovementsXmlParsingService.extractDeclarationData(any[Source[ByteString, ?]]))
          .thenReturn(EitherT.leftT[Future, DeclarationData](ParseError.NoElementFound("messageSender")))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())
        val request =
          fakeRequest(POST, Source.single(ByteString(elementNotFoundXml.mkString)), movementId, Some(triggerId), Some(MessageType.DeclarationData.code))

        val result: Future[Result] =
          controller.createMovement(eoriNumber, MovementType.Departure)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Element messageSender not found"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("WRITE")))
        )(any[ExecutionContext])
        verifyNoMoreInteractions(mockInternalAuthActionProvider)
      }

      "contains message to indicate too many elements found" in {

        val tooManyFoundXml: NodeSeq =
          <CC015C>
            <messageSender>GB1234</messageSender>
            <messageSender>XI1234</messageSender>
          </CC015C>

        val eoriNumber: EORINumber = arbitrary[EORINumber].sample.get

        when(mockMovementsXmlParsingService.extractDeclarationData(any[Source[ByteString, ?]]))
          .thenReturn(EitherT.leftT[Future, DeclarationData](ParseError.TooManyElementsFound("messageSender")))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())
        val request =
          fakeRequest(POST, Source.single(ByteString(tooManyFoundXml.mkString)), movementId, Some(triggerId), Some(MessageType.DeclarationData.code))

        val result: Future[Result] =
          controller.createMovement(eoriNumber, MovementType.Departure)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Found too many elements of type messageSender"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("WRITE")))
        )(any[ExecutionContext])
        verifyNoMoreInteractions(mockInternalAuthActionProvider)
      }

      "contains message to indicate date time failure" in {

        val tooManyFoundXml: NodeSeq =
          <CC015C>
            <messageSender>GB1234</messageSender>
            <preparationDateAndTime>no</preparationDateAndTime>
          </CC015C>

        val cs: CharSequence = "no"

        val eoriNumber: EORINumber = arbitrary[EORINumber].sample.get

        when(mockMovementsXmlParsingService.extractDeclarationData(any[Source[ByteString, ?]]))
          .thenReturn(
            EitherT.leftT[Future, DeclarationData](
              ParseError.BadDateTime("preparationDateAndTime", new DateTimeParseException("Text 'no' could not be parsed at index 0", cs, 0))
            )
          )

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())
        val request =
          fakeRequest(POST, Source.single(ByteString(tooManyFoundXml.mkString)), movementId, Some(triggerId), Some(MessageType.DeclarationData.code))

        val result: Future[Result] =
          controller.createMovement(eoriNumber, MovementType.Departure)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Could not parse datetime for preparationDateAndTime: Text 'no' could not be parsed at index 0"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("WRITE")))
        )(any[ExecutionContext])
        verifyNoMoreInteractions(mockInternalAuthActionProvider)
      }
    }
    "must return INTERNAL_SERVICE_ERROR" - {

      "when an invalid xml causes an unknown ParseError to be thrown" in {

        val unknownErrorXml: String =
          "<CC015C><messageSender>GB1234</messageSender>"

        val eoriNumber: EORINumber = arbitrary[EORINumber].sample.get

        when(mockMovementsXmlParsingService.extractDeclarationData(any[Source[ByteString, ?]]))
          .thenReturn(EitherT.leftT[Future, DeclarationData](ParseError.UnexpectedError(Some(new IllegalArgumentException()))))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = FakeRequest(
          method = POST,
          uri = routes.MovementsController.createMovement(eoriNumber, MovementType.Departure).url,
          headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML, Constants.APIVersionHeaderKey -> APIVersionHeader.V2_1.value)),
          body = Source.single(ByteString(unknownErrorXml))
        )

        val result: Future[Result] =
          controller.createMovement(eoriNumber, MovementType.Departure)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("WRITE")))
        )(any[ExecutionContext])
        verifyNoMoreInteractions(mockInternalAuthActionProvider)
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

    val mrn: MovementReferenceNumber = arbitraryMovementReferenceNumber.arbitrary.sample.get
    val eoriNumber: EORINumber       = arbitrary[EORINumber].sample.get

    val now: OffsetDateTime = OffsetDateTime.now
    val arrivalData         = ArrivalData(Some(eoriNumber), OffsetDateTime.now(ZoneId.of("UTC")), mrn)

    val message: Message =
      arbitraryMessage.arbitrary.sample.get.copy(
        id = messageId,
        generated = Some(now),
        received = now,
        triggerId = Some(triggerId),
        uri = Some(new URI("http://www.google.com"))
      )

    lazy val messageFactoryEither: EitherT[Future, StreamError, Message] =
      EitherT.rightT(message)
    val movement: Movement = arbitrary[Movement].sample.value.copy(
      _id = movementId,
      enrollmentEORINumber = eoriNumber,
      movementEORINumber = Some(eoriNumber),
      created = now,
      updated = now,
      messages = Vector(message)
    )

    lazy val arrivalDataEither: EitherT[Future, ParseError, ArrivalData] =
      EitherT.rightT(arrivalData)

    "must return OK if XML data extraction is successful and mark the movement as '2.1' with 2.1 header" in {

      val tempFile = SingletonTemporaryFileCreator.create()

      when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

      when(mockMovementsXmlParsingService.extractArrivalData(any[Source[ByteString, ?]]))
        .thenReturn(arrivalDataEither)

      when(
        mockMovementFactory.createArrival(
          any[String].asInstanceOf[MovementId],
          any[String].asInstanceOf[EORINumber],
          any[String].asInstanceOf[MovementType],
          any[ArrivalData],
          any[Message],
          any[OffsetDateTime],
          any[OffsetDateTime],
          Some(any[String].asInstanceOf[ClientId]),
          eqTo(V2_1)
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
          any[Source[ByteString, ?]],
          eqTo(MessageStatus.Processing)
        )(any[HeaderCarrier])
      )
        .thenReturn(messageFactoryEither)

      when(mockPersistenceService.insertMovement(eqTo(movement)))
        .thenReturn(EitherT.liftF(Future.unit))

      //      val request = fakeRequest[Source[ByteString, ?]](POST, validXmlStream, Some(MessageType.ArrivalNotification.code))
      val request = fakeRequest(
        POST,
        validXmlStream,
        movementId,
        Some(triggerId),
        Some(MessageType.ArrivalNotification.code)
      )

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
        any[Source[ByteString, ?]],
        eqTo(MessageStatus.Processing)
      )(any[HeaderCarrier])

      verify(mockInternalAuthActionProvider, times(1)).apply(
        eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("WRITE")))
      )(any[ExecutionContext])
      verifyNoMoreInteractions(mockInternalAuthActionProvider)
    }

    "must return OK if XML data extraction is successful and mark the movement as '3.0' with a '3.0' header" in {

      val tempFile = SingletonTemporaryFileCreator.create()

      when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

      when(mockMovementsXmlParsingService.extractArrivalData(any[Source[ByteString, ?]]))
        .thenReturn(arrivalDataEither)

      when(
        mockMovementFactory.createArrival(
          any[String].asInstanceOf[MovementId],
          any[String].asInstanceOf[EORINumber],
          any[String].asInstanceOf[MovementType],
          any[ArrivalData],
          any[Message],
          any[OffsetDateTime],
          any[OffsetDateTime],
          Some(any[String].asInstanceOf[ClientId]),
          eqTo(V3_0)
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
          any[Source[ByteString, ?]],
          eqTo(MessageStatus.Processing)
        )(any[HeaderCarrier])
      )
        .thenReturn(messageFactoryEither)

      when(mockPersistenceService.insertMovement(eqTo(movement)))
        .thenReturn(EitherT.liftF(Future.unit))

      val request = fakeRequest(
        POST,
        validXmlStream,
        movementId,
        Some(triggerId),
        Some(MessageType.ArrivalNotification.code),
        FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML, Constants.APIVersionHeaderKey -> APIVersionHeader.V3_0.value))
      )

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
        any[Source[ByteString, ?]],
        eqTo(MessageStatus.Processing)
      )(any[HeaderCarrier])

      verify(mockInternalAuthActionProvider, times(1)).apply(
        eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("WRITE")))
      )(any[ExecutionContext])
      verifyNoMoreInteractions(mockInternalAuthActionProvider)
    }

    "must return BAD_REQUEST when XML data extraction fails" - {

      "contains message to indicate element not found" in {

        val elementNotFoundXml: NodeSeq =
          <CC007C></CC007C>

        when(mockMovementsXmlParsingService.extractArrivalData(any[Source[ByteString, ?]]))
          .thenReturn(EitherT.leftT[Future, ArrivalData](ParseError.NoElementFound("messageSender")))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        //        val request =
        //          fakeRequest(POST, Source.single(ByteString(elementNotFoundXml.mkString)), Some(MessageType.ArrivalNotification.code))
        val request = fakeRequest(
          POST,
          Source.single(ByteString(elementNotFoundXml.mkString)),
          movementId,
          Some(triggerId),
          Some(MessageType.ArrivalNotification.code)
        )

        val result: Future[Result] =
          controller.createMovement(eoriNumber, MovementType.Arrival)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Element messageSender not found"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("WRITE")))
        )(any[ExecutionContext])
        verifyNoMoreInteractions(mockInternalAuthActionProvider)
      }

      "contains message to indicate too many elements found" in {

        val tooManyFoundXml: NodeSeq =
          <CC007C>
            <messageSender>GB1234</messageSender>
            <messageSender>XI1234</messageSender>
          </CC007C>

        when(mockMovementsXmlParsingService.extractArrivalData(any[Source[ByteString, ?]]))
          .thenReturn(EitherT.leftT[Future, ArrivalData](ParseError.TooManyElementsFound("messageSender")))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        //        val request = fakeRequest(POST, Source.single(ByteString(tooManyFoundXml.mkString)), Some(MessageType.ArrivalNotification.code))
        val request = fakeRequest(
          POST,
          Source.single(ByteString(tooManyFoundXml.mkString)),
          movementId,
          Some(triggerId),
          Some(MessageType.ArrivalNotification.code)
        )

        val result: Future[Result] =
          controller.createMovement(eoriNumber, MovementType.Arrival)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Found too many elements of type messageSender"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("WRITE")))
        )(any[ExecutionContext])
        verifyNoMoreInteractions(mockInternalAuthActionProvider)
      }

      "contains message to indicate date time failure" in {

        val tooManyFoundXml: NodeSeq =
          <CC007C>
            <messageSender>GB1234</messageSender>
            <preparationDateAndTime>no</preparationDateAndTime>
          </CC007C>

        val cs: CharSequence = "no"

        when(mockMovementsXmlParsingService.extractArrivalData(any[Source[ByteString, ?]]))
          .thenReturn(
            EitherT.leftT[Future, ArrivalData](
              ParseError.BadDateTime("preparationDateAndTime", new DateTimeParseException("Text 'no' could not be parsed at index 0", cs, 0))
            )
          )

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        //        val request = fakeRequest(POST, Source.single(ByteString(tooManyFoundXml.mkString)), Some(MessageType.ArrivalNotification.code))
        val request = fakeRequest(
          POST,
          Source.single(ByteString(tooManyFoundXml.mkString)),
          movementId,
          Some(triggerId),
          Some(MessageType.ArrivalNotification.code)
        )

        val result: Future[Result] =
          controller.createMovement(eoriNumber, MovementType.Arrival)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Could not parse datetime for preparationDateAndTime: Text 'no' could not be parsed at index 0"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("WRITE")))
        )(any[ExecutionContext])
        verifyNoMoreInteractions(mockInternalAuthActionProvider)
      }
    }

    "must return INTERNAL_SERVICE_ERROR" - {

      "when an invalid xml causes an unknown ParseError to be thrown" in {

        val unknownErrorXml: String =
          "<CC007C><messageSender>GB1234</messageSender>"

        when(mockMovementsXmlParsingService.extractArrivalData(any[Source[ByteString, ?]]))
          .thenReturn(EitherT.leftT[Future, ArrivalData](ParseError.UnexpectedError(Some(new IllegalArgumentException()))))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = FakeRequest(
          method = POST,
          uri = routes.MovementsController.createMovement(eoriNumber, MovementType.Arrival).url,
          headers = FakeHeaders(
            Seq(
              HeaderNames.CONTENT_TYPE      -> MimeTypes.XML,
              "X-Message-Type"              -> MessageType.ArrivalNotification.code,
              Constants.APIVersionHeaderKey -> APIVersionHeader.V2_1.value
            )
          ),
          body = Source.single(ByteString(unknownErrorXml))
        )

        val result: Future[Result] =
          controller.createMovement(eoriNumber, MovementType.Arrival)(request)

        // status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("WRITE")))
        )(any[ExecutionContext])
        verifyNoMoreInteractions(mockInternalAuthActionProvider)
      }

    }
  }

  "createEmptyMovement" - {
    val eoriNumber: EORINumber = arbitrary[EORINumber].sample.get

    val now: OffsetDateTime = OffsetDateTime.now
    lazy val emptyMovement  = arbitrary[Movement].sample.value.copy(
      _id = movementId,
      enrollmentEORINumber = eoriNumber,
      movementEORINumber = None,
      created = now,
      updated = now,
      messages = Vector.empty[Message]
    )

    val message: Message =
      arbitraryMessage.arbitrary.sample.get.copy(
        id = messageId,
        generated = Some(now),
        received = now,
        triggerId = Some(triggerId),
        uri = Some(new URI("http://www.google.com"))
      )

    val movement: Movement = arbitrary[Movement].sample.value.copy(
      _id = movementId,
      enrollmentEORINumber = eoriNumber,
      movementEORINumber = Some(eoriNumber),
      created = now,
      updated = now,
      messages = Vector(message)
    )

    def streamRequest(
      headers: Seq[(String, String)] = Seq("X-Message-Type" -> emptyMovement.movementType.value, Constants.APIVersionHeaderKey -> APIVersionHeader.V2_1.value)
    ): Request[Source[ByteString, ?]] =
      FakeRequest(
        method = "POST",
        uri = routes.MovementsController.createMovement(eoriNumber, emptyMovement.movementType).url,
        headers = FakeHeaders(headers),
        body = Source.empty[ByteString]
      )

    "must return OK if XML data extraction is successful and mark the movement as '2.1' with 2.1 header" in {

      when(
        mockMovementFactory.createEmptyMovement(
          any[String].asInstanceOf[EORINumber],
          any[String].asInstanceOf[MovementType],
          any[Message],
          any[OffsetDateTime],
          any[OffsetDateTime],
          Some(any[String].asInstanceOf[ClientId]),
          eqTo(V2_1)
        )
      )
        .thenReturn(movement)

      when(mockPersistenceService.insertMovement(eqTo(movement)))
        .thenReturn(EitherT.liftF(Future.unit))

      val result: Future[Result] =
        controller.createMovement(eoriNumber, MovementType.Departure)(streamRequest())

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj(
        "movementId" -> movementId.value,
        "messageId"  -> messageId.value
      )

      verify(mockInternalAuthActionProvider, times(1)).apply(
        eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("WRITE")))
      )(any[ExecutionContext])
      verifyNoMoreInteractions(mockInternalAuthActionProvider)
    }

    "must return OK if XML data extraction is successful and mark the movement as '3.0' with a '3.0' header" in {
      when(
        mockMovementFactory.createEmptyMovement(
          any[String].asInstanceOf[EORINumber],
          any[String].asInstanceOf[MovementType],
          any[Message],
          any[OffsetDateTime],
          any[OffsetDateTime],
          Some(any[String].asInstanceOf[ClientId]),
          eqTo(V3_0)
        )
      )
        .thenReturn(movement)

      when(mockPersistenceService.insertMovement(eqTo(movement)))
        .thenReturn(EitherT.liftF(Future.unit))

      val result: Future[Result] =
        controller.createMovement(eoriNumber, MovementType.Departure)(
          streamRequest(Seq("X-Message-Type" -> emptyMovement.movementType.value, Constants.APIVersionHeaderKey -> APIVersionHeader.V3_0.value))
        )

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj(
        "movementId" -> movementId.value,
        "messageId"  -> messageId.value
      )

      verify(mockInternalAuthActionProvider, times(1)).apply(
        eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("WRITE")))
      )(any[ExecutionContext])
      verifyNoMoreInteractions(mockInternalAuthActionProvider)
    }

    "must return INTERNAL_SERVICE_ERROR when insert fails" in {

      when(
        mockMovementFactory.createEmptyMovement(
          any[String].asInstanceOf[EORINumber],
          any[String].asInstanceOf[MovementType],
          any[Message],
          any[OffsetDateTime],
          any[OffsetDateTime],
          Some(any[String].asInstanceOf[ClientId]),
          any[APIVersionHeader]
        )
      )
        .thenReturn(movement)

      when(mockPersistenceService.insertMovement(eqTo(movement)))
        .thenReturn(EitherT.leftT[Future, Unit](MongoError.InsertNotAcknowledged(s"Insert failed")))

      val result: Future[Result] =
        controller.createMovement(eoriNumber, emptyMovement.movementType)(streamRequest())

      status(result) mustBe INTERNAL_SERVER_ERROR
      contentAsJson(result) mustBe Json.obj(
        "code"    -> "INTERNAL_SERVER_ERROR",
        "message" -> "Insert failed"
      )

      verify(mockInternalAuthActionProvider, times(1)).apply(
        eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("WRITE")))
      )(any[ExecutionContext])
      verifyNoMoreInteractions(mockInternalAuthActionProvider)
    }
  }

  for (movementType <- Seq(MovementType.Departure, MovementType.Arrival)) {
    s"when the movement type equals $movementType" - {

      "getMovementWithoutMessages" - {
        val eoriNumber: EORINumber = arbitrary[EORINumber].sample.get

        val now: OffsetDateTime = OffsetDateTime.now
        val message: Message    =
          arbitraryMessage.arbitrary.sample.get.copy(
            id = messageId,
            generated = Some(now),
            received = now,
            triggerId = Some(triggerId),
            uri = Some(new URI("http://www.google.com"))
          )

        val movement: Movement = arbitrary[Movement].sample.value.copy(
          _id = movementId,
          enrollmentEORINumber = eoriNumber,
          movementEORINumber = Some(eoriNumber),
          created = now,
          updated = now,
          messages = Vector(message)
        )
        val request = FakeRequest("GET", routes.MovementsController.getMovementWithoutMessages(eoriNumber, movementType, movementId).url)

        "must return OK if departure found" in {

          when(mockPersistenceService.getMovementWithoutMessages(EORINumber(any()), MovementId(any()), eqTo(movementType)))
            .thenReturn(EitherT.rightT[Future, MovementWithoutMessages](MovementWithoutMessages.fromMovement(movement)))

          val result: Future[Result] = controller.getMovementWithoutMessages(eoriNumber, movementType, movementId)(request)

          status(result) mustBe OK
          contentAsJson(result) mustBe Json.toJson(MovementWithoutMessages.fromMovement(movement))

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("READ")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }

        "must return NOT_FOUND if no departure found" in {
          when(mockPersistenceService.getMovementWithoutMessages(EORINumber(any()), MovementId(any()), eqTo(movementType)))
            .thenReturn(EitherT.leftT[Future, MovementWithoutMessages](MongoError.DocumentNotFound("test")))

          val result: Future[Result] = controller.getMovementWithoutMessages(eoriNumber, movementType, movementId)(request)

          status(result) mustBe NOT_FOUND

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("READ")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }

        "must return INTERNAL_SERVER_ERROR if repository has an error" in {
          when(mockPersistenceService.getMovementWithoutMessages(EORINumber(any()), MovementId(any()), eqTo(movementType)))
            .thenReturn(EitherT.leftT[Future, MovementWithoutMessages](MongoError.UnexpectedError(Some(new Throwable("test")))))

          val result: Future[Result] = controller.getMovementWithoutMessages(eoriNumber, movementType, movementId)(request)

          status(result) mustBe INTERNAL_SERVER_ERROR

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("READ")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }
      }

      "getMessage" - {

        val eoriNumber: EORINumber = arbitrary[EORINumber].sample.get
        val now: OffsetDateTime    = OffsetDateTime.now
        val message: Message       =
          arbitraryMessage.arbitrary.sample.get.copy(
            id = messageId,
            generated = Some(now),
            received = now,
            triggerId = Some(triggerId),
            uri = Some(new URI("http://www.google.com"))
          )
        val movement: Movement = arbitrary[Movement].sample.value.copy(
          _id = movementId,
          enrollmentEORINumber = eoriNumber,
          movementEORINumber = Some(eoriNumber),
          created = now,
          updated = now,
          messages = Vector(message)
        )

        val request = FakeRequest("GET", routes.MovementsController.getMessage(eoriNumber, movementType, movementId, messageId).url)

        "must return OK if message found in the correct format" in {
          val messageResponse = MessageResponse.fromMessageWithBody(movement.messages.head)

          when(mockPersistenceService.getSingleMessage(EORINumber(any()), MovementId(any()), MessageId(any()), eqTo(movementType)))
            .thenReturn(EitherT.rightT[Future, MessageResponse](messageResponse))

          val result: Future[Result] = controller.getMessage(eoriNumber, movementType, movementId, messageId)(request)

          status(result) mustBe OK
          contentAsJson(result) mustBe Json.toJson(messageResponse)

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("READ")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }

        "must return OK along with uri if message found in the correct format" in {
          val messageResponse = MessageResponse.fromMessageWithoutBody(movement.messages.head)

          when(mockPersistenceService.getSingleMessage(EORINumber(any()), MovementId(any()), MessageId(any()), eqTo(movementType)))
            .thenReturn(EitherT.rightT[Future, MessageResponse](messageResponse))

          val result: Future[Result] = controller.getMessage(eoriNumber, movementType, movementId, messageId)(request)

          status(result) mustBe OK
          contentAsJson(result) mustBe Json.toJson(messageResponse)

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("READ")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }

        "must return NOT_FOUND if no message found" in {

          when(mockPersistenceService.getSingleMessage(EORINumber(any()), MovementId(any()), MessageId(any()), eqTo(movementType)))
            .thenReturn(EitherT.leftT[Future, MessageResponse](MongoError.DocumentNotFound("test")))

          val result: Future[Result] = controller.getMessage(eoriNumber, movementType, movementId, messageId)(request)

          status(result) mustBe NOT_FOUND

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("READ")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }

        "must return INTERNAL_SERVICE_ERROR when a database error is thrown" in {

          when(mockPersistenceService.getSingleMessage(EORINumber(any()), MovementId(any()), MessageId(any()), eqTo(movementType)))
            .thenReturn(EitherT.leftT[Future, MessageResponse](MongoError.UnexpectedError(Some(new Throwable("test")))))

          val result: Future[Result] = controller.getMessage(eoriNumber, movementType, movementId, messageId)(request)

          status(result) mustBe INTERNAL_SERVER_ERROR

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("READ")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }
      }

      "getMessages" - {

        "must return OK and a list of message ids" in {

          val eoriNumber: EORINumber = arbitrary[EORINumber].sample.get
          val now: OffsetDateTime    = OffsetDateTime.now
          val request                = FakeRequest("GET", routes.MovementsController.getMessages(eoriNumber, movementType, movementId).url)
          val message: Message       =
            arbitraryMessage.arbitrary.sample.get.copy(
              id = messageId,
              generated = Some(now),
              received = now,
              triggerId = Some(triggerId),
              uri = Some(new URI("http://www.google.com"))
            )

          val movement: Movement = arbitrary[Movement].sample.value.copy(
            _id = movementId,
            enrollmentEORINumber = eoriNumber,
            movementEORINumber = Some(eoriNumber),
            created = now,
            updated = now,
            messages = Vector(message)
          )

          val messageResponses = MessageResponse.fromMessageWithoutBody(movement.messages.head)

          lazy val messageResponseList       = Vector(messageResponses)
          lazy val paginationMesssageSummary = PaginationMessageSummary(TotalCount(0), messageResponseList)

          when(
            mockPersistenceService.getMovementWithoutMessages(EORINumber(eqTo(eoriNumber.value)), MovementId(eqTo(movementId.value)), eqTo(movementType))
          )
            .thenReturn(
              EitherT.rightT[Future, MovementWithoutMessages](
                MovementWithoutMessages(movementId, eoriNumber, None, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock), V2_1)
              )
            )

          when(mockPersistenceService.getMessages(EORINumber(any()), MovementId(any()), eqTo(movementType), eqTo(None), eqTo(None), eqTo(None), eqTo(None)))
            .thenReturn(EitherT.rightT[Future, PaginationMessageSummary](paginationMesssageSummary))
          val result: Future[Result] = controller.getMessages(eoriNumber, movementType, movementId, None, None, None, None)(request)

          status(result) mustBe OK
          contentAsJson(result) mustBe Json.toJson(paginationMesssageSummary)

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("READ")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }

        "must return empty list if no messages found for given movement" in {

          val eoriNumber: EORINumber = arbitrary[EORINumber].sample.get
          val request                = FakeRequest("GET", routes.MovementsController.getMessages(eoriNumber, movementType, movementId).url)

          when(
            mockPersistenceService.getMovementWithoutMessages(EORINumber(eqTo(eoriNumber.value)), MovementId(eqTo(movementId.value)), eqTo(movementType))
          )
            .thenReturn(
              EitherT.rightT[Future, MovementWithoutMessages](
                MovementWithoutMessages(movementId, eoriNumber, None, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock), V2_1)
              )
            )

          lazy val paginationMesssageSummary = PaginationMessageSummary(TotalCount(0), Vector.empty[MessageResponse])

          when(
            mockPersistenceService.getMessages(
              EORINumber(eqTo(eoriNumber.value)),
              MovementId(eqTo(movementId.value)),
              eqTo(movementType),
              eqTo(None),
              eqTo(None),
              eqTo(None),
              eqTo(None)
            )
          )
            .thenReturn(EitherT.rightT[Future, PaginationMessageSummary](paginationMesssageSummary))

          val result: Future[Result] = controller.getMessages(eoriNumber, movementType, movementId, None, None, None, None)(request)

          status(result) mustBe OK
          contentAsJson(result) mustBe Json.toJson(paginationMesssageSummary)

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("READ")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }

        "must return NOT_FOUND if no departure found" in {

          val eoriNumber: EORINumber = arbitrary[EORINumber].sample.get
          val request                = FakeRequest("GET", routes.MovementsController.getMessages(eoriNumber, movementType, movementId).url)

          when(mockPersistenceService.getMovementWithoutMessages(EORINumber(eqTo(eoriNumber.value)), MovementId(eqTo(movementId.value)), eqTo(movementType)))
            .thenReturn(EitherT.leftT[Future, MovementWithoutMessages](MongoError.DocumentNotFound("test")))

          val result: Future[Result] = controller.getMessages(eoriNumber, movementType, movementId, None)(request)

          status(result) mustBe NOT_FOUND

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("READ")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }

        "must return INTERNAL_SERVER_ERROR when a database error is thrown" in {

          val eoriNumber: EORINumber = arbitrary[EORINumber].sample.get
          val request                = FakeRequest("GET", routes.MovementsController.getMessages(eoriNumber, movementType, movementId).url)

          when(
            mockPersistenceService.getMovementWithoutMessages(EORINumber(eqTo(eoriNumber.value)), MovementId(eqTo(movementId.value)), eqTo(movementType))
          )
            .thenReturn(
              EitherT.rightT[Future, MovementWithoutMessages](
                MovementWithoutMessages(movementId, eoriNumber, None, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock), V2_1)
              )
            )

          when(mockPersistenceService.getMessages(EORINumber(any()), MovementId(any()), eqTo(movementType), eqTo(None), eqTo(None), eqTo(None), eqTo(None)))
            .thenReturn(EitherT.leftT[Future, PaginationMessageSummary](UnexpectedError(None)))

          val result: Future[Result] = controller.getMessages(eoriNumber, movementType, movementId, None, None, None, None)(request)

          status(result) mustBe INTERNAL_SERVER_ERROR

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("READ")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }
      }

      "getMovementsForEori" - {

        val eoriNumber: EORINumber = arbitrary[EORINumber].sample.get

        val request = FakeRequest(
          "GET",
          routes.MovementsController
            .getMovementsForEori(eoriNumber, movementType, None, Some(EORINumber("GB1234")), None, Some(PageNumber(0)), Some(ItemCount(15)))
            .url
        )

        "must return OK if departures were found" in {
          val now: OffsetDateTime = OffsetDateTime.now
          val message: Message    =
            arbitraryMessage.arbitrary.sample.get.copy(
              id = messageId,
              generated = Some(now),
              received = now,
              triggerId = Some(triggerId),
              uri = Some(new URI("http://www.google.com"))
            )
          val movement: Movement = arbitrary[Movement].sample.value.copy(
            _id = movementId,
            enrollmentEORINumber = eoriNumber,
            movementEORINumber = Some(eoriNumber),
            created = now,
            updated = now,
            messages = Vector(message)
          )

          val response = MovementWithoutMessages.fromMovement(movement)

          lazy val paginationMovementSummary = PaginationMovementSummary(TotalCount(1), Vector(response))

          when(
            mockPersistenceService.getMovements(
              EORINumber(any()),
              eqTo(movementType),
              eqTo(None),
              eqTo(None),
              eqTo(None),
              eqTo(None),
              eqTo(None),
              eqTo(None),
              eqTo(None)
            )
          )
            .thenReturn(EitherT.rightT[Future, PaginationMovementSummary](paginationMovementSummary))

          val result: Future[Result] = controller.getMovementsForEori(eoriNumber, movementType)(request)
          status(result) mustBe OK

          contentAsJson(result) mustBe Json.toJson(paginationMovementSummary)

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("READ")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }

        "must return OK if departures were found and it match the updatedSince or movementEORI or movementReferenceNumber or localReferenceNumber or all these filters" in forAll(
          Gen.option(arbitrary[OffsetDateTime]),
          Gen.option(arbitrary[EORINumber]),
          Gen.option(arbitrary[MovementReferenceNumber]),
          Gen.option(arbitrary[PageNumber]),
          Gen.option(arbitrary[ItemCount]),
          Gen.option(arbitrary[LocalReferenceNumber])
        ) {
          (updatedSince, movementEORI, movementReferenceNumber, pageNumber, itemCount, localReferenceNumber) =>
            resetInternalAuth()

            val now: OffsetDateTime = OffsetDateTime.now
            val message: Message    =
              arbitraryMessage.arbitrary.sample.get.copy(
                id = messageId,
                generated = Some(now),
                received = now,
                triggerId = Some(triggerId),
                uri = Some(new URI("http://www.google.com"))
              )

            val movement: Movement = arbitrary[Movement].sample.value.copy(
              _id = movementId,
              enrollmentEORINumber = eoriNumber,
              movementEORINumber = Some(eoriNumber),
              created = now,
              updated = now,
              messages = Vector(message)
            )

            val response = MovementWithoutMessages.fromMovement(movement)

            lazy val paginationMovementSummary = PaginationMovementSummary(TotalCount(1), Vector(response))

            when(
              mockPersistenceService.getMovements(
                EORINumber(any()),
                eqTo(movementType),
                eqTo(updatedSince),
                eqTo(movementEORI),
                eqTo(movementReferenceNumber),
                eqTo(pageNumber),
                eqTo(itemCount),
                eqTo(None),
                eqTo(localReferenceNumber)
              )
            )
              .thenReturn(EitherT.rightT[Future, PaginationMovementSummary](paginationMovementSummary))

            val result: Future[Result] =
              controller.getMovementsForEori(
                eoriNumber,
                movementType,
                updatedSince,
                movementEORI,
                movementReferenceNumber,
                pageNumber,
                itemCount,
                None,
                localReferenceNumber
              )(request)

            status(result) mustBe OK

            contentAsJson(result) mustBe Json.toJson(paginationMovementSummary)
            verify(mockInternalAuthActionProvider, times(1)).apply(
              eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("READ")))
            )(any[ExecutionContext])
            verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }

        "must return empty list if no ids were found" in forAll(
          Gen.option(arbitrary[OffsetDateTime]),
          Gen.option(arbitrary[EORINumber]),
          Gen.option(arbitrary[MovementReferenceNumber]),
          Gen.option(arbitrary[LocalReferenceNumber])
        ) {
          (updatedSince, movementEORI, movementReferenceNumber, localReferenceNumber) =>
            resetInternalAuth()

            lazy val paginationMovementSummary = PaginationMovementSummary(TotalCount(0), Vector.empty[MovementWithoutMessages])
            when(
              mockPersistenceService.getMovements(
                EORINumber(any()),
                eqTo(movementType),
                eqTo(updatedSince),
                eqTo(movementEORI),
                eqTo(movementReferenceNumber),
                eqTo(None),
                eqTo(None),
                eqTo(None),
                eqTo(localReferenceNumber)
              )
            )
              .thenReturn(EitherT.rightT[Future, PaginationMovementSummary](paginationMovementSummary))

            val result: Future[Result] =
              controller.getMovementsForEori(
                eoriNumber,
                movementType,
                updatedSince,
                movementEORI,
                movementReferenceNumber,
                localReferenceNumber = localReferenceNumber
              )(request)

            status(result) mustBe OK
            contentAsJson(result) mustBe Json.toJson(paginationMovementSummary)
            verify(mockInternalAuthActionProvider, times(1)).apply(
              eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("READ")))
            )(any[ExecutionContext])
            verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }

        "must return INTERNAL_SERVICE_ERROR when a database error is thrown" in forAll(
          Gen.option(arbitrary[OffsetDateTime]),
          Gen.option(arbitrary[EORINumber]),
          Gen.option(arbitrary[MovementReferenceNumber]),
          Gen.option(arbitrary[LocalReferenceNumber])
        ) {
          (updatedSince, movementEORI, movementReferenceNumber, localReferenceNumber) =>
            resetInternalAuth()
            when(
              mockPersistenceService.getMovements(
                EORINumber(any()),
                any(),
                eqTo(updatedSince),
                eqTo(movementEORI),
                eqTo(movementReferenceNumber),
                eqTo(None),
                eqTo(None),
                eqTo(None),
                eqTo(localReferenceNumber)
              )
            )
              .thenReturn(EitherT.leftT[Future, PaginationMovementSummary](MongoError.UnexpectedError(Some(new Throwable("test")))))

            val result: Future[Result] =
              controller.getMovementsForEori(
                eoriNumber,
                movementType,
                updatedSince,
                movementEORI,
                movementReferenceNumber,
                localReferenceNumber = localReferenceNumber
              )(request)

            status(result) mustBe INTERNAL_SERVER_ERROR
            verify(mockInternalAuthActionProvider, times(1)).apply(
              eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements")), IAAction("READ")))
            )(any[ExecutionContext])
            verifyNoMoreInteractions(mockInternalAuthActionProvider)
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

    val clientId: ClientId  = arbClientId.arbitrary.sample.get
    val now: OffsetDateTime = OffsetDateTime.now
    val message1: Message   =
      arbitraryMessage.arbitrary.sample.get.copy(
        id = previousMessageId,
        generated = Some(now),
        received = now,
        uri = Some(new URI("http://www.google.com")),
        messageType = Some(messageType)
      )

    val message2: Message =
      arbitraryMessage.arbitrary.sample.get.copy(
        id = messageId,
        generated = Some(now),
        received = now,
        triggerId = Some(previousMessageId),
        uri = Some(new URI("http://www.google.com")),
        messageType = Some(MessageType.ReleaseForTransit)
      )

    val message3: Message =
      arbitraryMessage.arbitrary.sample.get.copy(
        id = messageId,
        generated = Some(now),
        received = now,
        uri = Some(new URI("http://www.google.com")),
        messageType = Some(MessageType.ReleaseForTransit)
      )

    val eoriNumber: EORINumber = arbitrary[EORINumber].sample.get

    val movement: Movement = arbitrary[Movement].sample.value.copy(
      _id = movementId,
      enrollmentEORINumber = eoriNumber,
      movementEORINumber = Some(eoriNumber),
      created = now,
      updated = now,
      messages = Vector(message1, message2),
      clientId = Some(clientId),
      apiVersion = APIVersionHeader.V2_1
    )

    lazy val messageResponseList = movement.messages.map(MessageResponse.fromMessageWithoutBody)

    lazy val messageFactoryEither: EitherT[Future, StreamError, Message] =
      EitherT.rightT(message2)

    lazy val messageFactory: EitherT[Future, StreamError, Message] =
      EitherT.rightT(message3)

    "must return OK and insert record in db if inserting new message type" in {

      val tempFile = SingletonTemporaryFileCreator.create()

      when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

      when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, ?]], any[MessageType]))
        .thenReturn(messageDataEither)

      when(mockPersistenceService.getMessageIdsAndType(MovementId(eqTo(movementId.value))))
        .thenReturn(EitherT.rightT[Future, Vector[MessageResponse]](Vector(messageResponseList.head)))

      when(
        mockMessageFactory.create(
          MovementId(eqTo(movementId.value)),
          any[MessageType],
          any[OffsetDateTime],
          any[OffsetDateTime],
          any[Option[MessageId]],
          any[Long],
          any[Source[ByteString, ?]],
          any[MessageStatus]
        )(any[HeaderCarrier])
      )
        .thenReturn(messageFactoryEither)

      when(mockPersistenceService.attachMessage(any[String].asInstanceOf[MovementId], any[Message], any[Option[MovementReferenceNumber]], any[OffsetDateTime]))
        .thenReturn(EitherT.rightT[Future, Unit](()))

      when(mockPersistenceService.getMovementEori(any[String].asInstanceOf[MovementId]))
        .thenReturn(EitherT.rightT[Future, MovementWithEori](MovementWithEori.fromMovement(movement)))

      val request = fakeRequest(POST, validXmlStream, movementId, Some(previousMessageId), Some(MessageType.ReleaseForTransit.code))

      val result: Future[Result] =
        controller.updateMovement(movementId, Some(previousMessageId))(request)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.toJson(
        UpdateMovementResponse(messageId = message2.id, eori = eoriNumber, clientId = Some(clientId), apiVersion = V2_1, sendNotification = true)
      )

      verify(mockInternalAuthActionProvider, times(1)).apply(
        eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
      )(any[ExecutionContext])
      verifyNoMoreInteractions(mockInternalAuthActionProvider)
    }

    "must return OK and insert record in db if inserting new message type with None triggerId" in {

      when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, ?]], any[MessageType]))
        .thenReturn(messageDataEither)

      when(mockPersistenceService.getMessageIdsAndType(MovementId(eqTo(movementId.value))))
        .thenReturn(EitherT.rightT[Future, Vector[MessageResponse]](messageResponseList))

      when(
        mockMessageFactory.create(
          MovementId(eqTo(movementId.value)),
          any[MessageType],
          any[OffsetDateTime],
          any[OffsetDateTime],
          any[Option[MessageId]],
          any[Long],
          any[Source[ByteString, ?]],
          any[MessageStatus]
        )(any[HeaderCarrier])
      )
        .thenReturn(messageFactory)

      when(mockPersistenceService.attachMessage(any[String].asInstanceOf[MovementId], any[Message], any[Option[MovementReferenceNumber]], any[OffsetDateTime]))
        .thenReturn(EitherT.rightT[Future, Unit](()))

      when(mockPersistenceService.getMovementEori(any[String].asInstanceOf[MovementId]))
        .thenReturn(EitherT.rightT[Future, MovementWithEori](MovementWithEori.fromMovement(movement)))

      val request = fakeRequest(POST, validXmlStream, movementId, None, Some(MessageType.ReleaseForTransit.code))

      val result: Future[Result] =
        controller.updateMovement(movementId, None)(request)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.toJson(
        UpdateMovementResponse(messageId = message3.id, eori = eoriNumber, clientId = Some(clientId), apiVersion = V2_1, sendNotification = true)
      )

      verify(mockInternalAuthActionProvider, times(1)).apply(
        eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
      )(any[ExecutionContext])
      verifyNoMoreInteractions(mockInternalAuthActionProvider)
    }

    "must return OK without inserting in db if inserting a duplicate message" in {

      val tempFile = SingletonTemporaryFileCreator.create()

      when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

      when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, ?]], any[MessageType]))
        .thenReturn(messageDataEither)

      when(mockPersistenceService.getMessageIdsAndType(MovementId(eqTo(movementId.value))))
        .thenReturn(EitherT.rightT[Future, Vector[MessageResponse]](messageResponseList))

      when(mockPersistenceService.getMovementEori(any[String].asInstanceOf[MovementId]))
        .thenReturn(EitherT.rightT[Future, MovementWithEori](MovementWithEori.fromMovement(movement)))

      val request = fakeRequest(POST, validXmlStream, movementId, message2.triggerId, message2.messageType.map(_.code))

      val result: Future[Result] =
        controller.updateMovement(movementId, message2.triggerId)(request)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.toJson(
        UpdateMovementResponse(messageId = message1.id, eori = eoriNumber, clientId = Some(clientId), apiVersion = V2_1, sendNotification = false)
      )

      verify(mockInternalAuthActionProvider, times(1)).apply(
        eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
      )(any[ExecutionContext])
      verifyNoMoreInteractions(mockInternalAuthActionProvider)
    }

    "must return BAD_REQUEST, NOT_FOUND when XML data extraction fails, Record not found in database" - {

      "contains message to indicate date time failure" in {

        val cs: CharSequence = "invalid"

        val xml: NodeSeq =
          <CC009C>
            <preparationDateAndTime>invalid</preparationDateAndTime>
          </CC009C>

        val xmlStream = Source.single(ByteString(xml.mkString))

        val tempFile = SingletonTemporaryFileCreator.create()
        when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

        when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, ?]], any[MessageType]))
          .thenReturn(
            EitherT.leftT[Future, MessageData](
              ParseError.BadDateTime("preparationDateAndTime", new DateTimeParseException("Text 'invalid' could not be parsed at index 0", cs, 0))
            )
          )

        val request = fakeRequest(POST, xmlStream, movementId, Some(triggerId), Some(messageType.code))

        val result: Future[Result] =
          controller.updateMovement(movementId, Some(triggerId))(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Could not parse datetime for preparationDateAndTime: Text 'invalid' could not be parsed at index 0"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
        )(any[ExecutionContext])
        verifyNoMoreInteractions(mockInternalAuthActionProvider)
      }

      "contains message to indicate update failed due to document with given id not found" in {

        when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, ?]], any[MessageType]))
          .thenReturn(messageDataEither)

        when(mockPersistenceService.getMessageIdsAndType(MovementId(eqTo(movementId.value))))
          .thenReturn(EitherT.rightT[Future, Vector[MessageResponse]](messageResponseList))

        val request = fakeRequest(POST, validXmlStream, movementId, Some(triggerId), Some(messageType.code))

        val result: Future[Result] =
          controller.updateMovement(movementId, Some(triggerId))(request)

        status(result) mustBe NOT_FOUND
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "NOT_FOUND",
          "message" -> s"Message ID ${triggerId.value} for movement ID ${movementId.value} was not found"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
        )(any[ExecutionContext])
        verifyNoMoreInteractions(mockInternalAuthActionProvider)
      }

      "contains message to indicate message type header not supplied" in {

        val tempFile = SingletonTemporaryFileCreator.create()

        when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

        val request = fakeRequest(
          method = POST,
          body = Source.single(ByteString(validXml.mkString)),
          movementId = movementId,
          triggerId = Some(triggerId),
          messageType = None,
          headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML))
        )

        val result: Future[Result] =
          controller.updateMovement(movementId, Some(triggerId))(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Missing X-Message-Type header value"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
        )(any[ExecutionContext])
        verifyNoMoreInteractions(mockInternalAuthActionProvider)
      }

      "contains message to indicate the given message type is invalid" in {

        val tempFile = SingletonTemporaryFileCreator.create()

        when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

        //        val request = fakeRequest(POST, validXmlStream, Some("invalid"), FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML)))
        val request = fakeRequest(
          method = POST,
          body = validXmlStream,
          movementId = movementId,
          triggerId = Some(triggerId),
          messageType = Some("invalid"),
          headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML))
        )

        val result: Future[Result] =
          controller.updateMovement(movementId, Some(triggerId))(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Invalid X-Message-Type header value: invalid"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
        )(any[ExecutionContext])
        verifyNoMoreInteractions(mockInternalAuthActionProvider)
      }

    }

    "must return INTERNAL_SERVICE_ERROR" - {

      "when an invalid xml causes an unknown ParseError to be thrown" in {

        val unknownErrorXml: String =
          "<CC007C><messageSender/>GB1234"

        when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, ?]], any[MessageType]))
          .thenReturn(EitherT.leftT[Future, MessageData](ParseError.UnexpectedError(Some(new IllegalArgumentException()))))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = FakeRequest(
          method = POST,
          uri = routes.MovementsController.updateMovement(movementId, Some(triggerId)).url,
          headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML, "X-Message-Type" -> messageType.code)),
          body = Source.single(ByteString(unknownErrorXml))
        )

        val result: Future[Result] =
          controller.updateMovement(movementId, Some(triggerId))(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
        )(any[ExecutionContext])
        verifyNoMoreInteractions(mockInternalAuthActionProvider)
      }

    }
  }

  "updateMovement for an empty message" - {

    lazy val message =
      arbitraryMessage.arbitrary.sample.get.copy(
        messageId,
        generated = None,
        messageType = None,
        triggerId = None,
        uri = None,
        body = None,
        status = Some(MessageStatus.Pending)
      )

    val now: OffsetDateTime    = OffsetDateTime.now
    val eoriNumber: EORINumber = arbitrary[EORINumber].sample.get
    val clientId: ClientId     = arbitrary[ClientId].sample.get
    val movement: Movement     = arbitrary[Movement].sample.value.copy(
      _id = movementId,
      enrollmentEORINumber = eoriNumber,
      movementEORINumber = Some(eoriNumber),
      created = now,
      updated = now,
      messages = Vector(message),
      clientId = Some(clientId),
      apiVersion = APIVersionHeader.V2_1
    )

    lazy val request = FakeRequest(
      method = "POST",
      uri = routes.MovementsController.updateMovement(movementId, None).url,
      headers = FakeHeaders(Seq.empty[(String, String)]),
      body = Source.empty[ByteString]
    )

    "must return OK if successfully attaches an empty message to a movement" in {

      when(
        mockMessageFactory.createEmptyMessage(
          any[Option[MessageType]],
          any[OffsetDateTime]
        )
      )
        .thenReturn(message)

      when(mockPersistenceService.attachMessage(any[String].asInstanceOf[MovementId], any[Message], any[Option[MovementReferenceNumber]], any[OffsetDateTime]))
        .thenReturn(EitherT.rightT[Future, Unit](()))

      when(mockPersistenceService.getMovementEori(any[String].asInstanceOf[MovementId]))
        .thenReturn(EitherT.rightT[Future, MovementWithEori](MovementWithEori.fromMovement(movement)))

      val result: Future[Result] =
        controller.updateMovement(movementId, None)(request)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.toJson(
        UpdateMovementResponse(messageId = messageId, eori = eoriNumber, clientId = Some(clientId), apiVersion = V2_1, sendNotification = true)
      )

      verify(mockInternalAuthActionProvider, times(1)).apply(
        eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
      )(any[ExecutionContext])
      verifyNoMoreInteractions(mockInternalAuthActionProvider)
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

      when(mockPersistenceService.attachMessage(any[String].asInstanceOf[MovementId], any[Message], any[Option[MovementReferenceNumber]], any[OffsetDateTime]))
        .thenReturn(EitherT.leftT[Future, Unit](MongoError.DocumentNotFound(errorMessage)))

      val result: Future[Result] =
        controller.updateMovement(movementId, None)(request)

      status(result) mustBe NOT_FOUND
      contentAsJson(result) mustBe Json.obj(
        "code"    -> "NOT_FOUND",
        "message" -> errorMessage
      )

      verify(mockInternalAuthActionProvider, times(1)).apply(
        eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
      )(any[ExecutionContext])
      verifyNoMoreInteractions(mockInternalAuthActionProvider)
    }

    "must return INTERNAL_SERVER_ERROR when an unexpected failure happens during message creation in the DB" in {

      when(
        mockMessageFactory.createEmptyMessage(
          any[Option[MessageType]],
          any[OffsetDateTime]
        )
      )
        .thenReturn(message)

      when(mockPersistenceService.attachMessage(any[String].asInstanceOf[MovementId], any[Message], any[Option[MovementReferenceNumber]], any[OffsetDateTime]))
        .thenReturn(EitherT.leftT[Future, Unit](MongoError.UnexpectedError(None)))

      val result: Future[Result] =
        controller.updateMovement(movementId, None)(request)

      status(result) mustBe INTERNAL_SERVER_ERROR
      contentAsJson(result) mustBe Json.obj(
        "code"    -> "INTERNAL_SERVER_ERROR",
        "message" -> "Internal server error"
      )

      verify(mockInternalAuthActionProvider, times(1)).apply(
        eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
      )(any[ExecutionContext])
      verifyNoMoreInteractions(mockInternalAuthActionProvider)
    }

  }

  "updateMessage" - {

    "for requests initiated from the trader or Upscan" - {

      "must return OK if successful given an object store URI, message type and status" - {

        "if the message is a departure message and the first one in the movement" in forAll(
          arbitrary[EORINumber],
          arbitrary[MovementId],
          arbitrary[MessageId],
          Gen.oneOf(MessageType.departureRequestValues),
          arbitraryLRN.arbitrary,
          arbitraryMessageSender.arbitrary
        ) {
          (eori, movementId, messageId, messageType, lrn, messageSender) =>
            resetInternalAuth()
            val extractDataEither: EitherT[Future, ParseError, Option[ExtractedData]] =
              if (messageType == MessageType.DeclarationData)
                EitherT.liftF(Future.successful(Some(DeclarationData(Some(eori), OffsetDateTime.now(clock), lrn, messageSender))))
              else
                EitherT.liftF(Future.successful(None))

            val now: OffsetDateTime = OffsetDateTime.now

            val lrnOption: Option[LocalReferenceNumber] =
              if (messageType == MessageType.DeclarationData) Some(lrn)
              else None

            val messageSenderOption: Option[MessageSender] =
              if (messageType == MessageType.DeclarationData) Some(messageSender)
              else None

            val eoriOption: Option[EORINumber] =
              if (messageType == MessageType.DeclarationData) Some(eori)
              else None

            val generatedTime: OffsetDateTime = now.minusMinutes(1)
            val tempFile                      = SingletonTemporaryFileCreator.create()

            when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

            when(
              mockPersistenceService.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(MovementType.Departure))
            )
              .thenReturn(
                EitherT.rightT[Future, MovementWithoutMessages](
                  MovementWithoutMessages(movementId, eori, None, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock), V2_1)
                )
              )

            when(
              mockPersistenceService.getSingleMessage(
                EORINumber(eqTo(eori.value)),
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                eqTo(MovementType.Departure)
              )
            )
              .thenReturn(EitherT.rightT[Future, MessageResponse](MessageResponse(messageId, now, None, None, Some(MessageStatus.Pending), None)))

            when(
              mockObjectStoreService
                .getObjectStoreFile(ObjectStoreResourceLocation(eqTo("movements/abcdef0123456789/abc.xml")))(any[ExecutionContext], any[HeaderCarrier])
            )
              .thenReturn(EitherT.rightT[Future, Source[ByteString, ?]](Source.empty[ByteString]))

            when(
              mockMovementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, ?]])
            )
              .thenReturn(extractDataEither)

            when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, ?]], eqTo(messageType)))
              .thenReturn(EitherT.rightT[Future, MessageData](MessageData(generatedTime, None)))

            when(
              mockPersistenceService.updateMessage(
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                argThat(
                  UpdateMessageDataMatcher(
                    Some(ObjectStoreURI("transit-movements/movements/abcdef0123456789/abc.xml")),
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
              .thenReturn(EitherT.rightT[Future, Unit](()))

            when(
              mockPersistenceService.updateMovement(
                MovementId(eqTo(movementId.value)),
                eqTo(eoriOption),
                eqTo(None),
                eqTo(lrnOption),
                eqTo(messageSenderOption),
                any[OffsetDateTime]
              )
            )
              .thenReturn(EitherT.rightT[Future, Unit](()))

            val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
            val body    = Json.obj(
              "messageType"    -> messageType.code,
              "objectStoreURI" -> "transit-movements/movements/abcdef0123456789/abc.xml",
              "status"         -> "Success"
            )
            val request = FakeRequest(
              method = POST,
              uri = routes.MovementsController.updateMessage(eori, MovementType.Arrival, movementId, messageId).url,
              headers = headers,
              body = body
            )

            val result: Future[Result] =
              controller.updateMessage(eori, MovementType.Departure, movementId, messageId)(request)

            status(result) mustBe OK
            verify(mockPersistenceService, times(1)).updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              any[UpdateMessageData],
              any[OffsetDateTime]
            )
            verify(mockPersistenceService, times(1)).updateMovement(
              MovementId(eqTo(movementId.value)),
              eqTo(eoriOption),
              eqTo(None),
              eqTo(lrnOption),
              eqTo(messageSenderOption),
              any[OffsetDateTime]
            )

            verify(mockInternalAuthActionProvider, times(1)).apply(
              eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
            )(any[ExecutionContext])
            verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }

        "if the message is an arrival message and the first one in the movement" in forAll(
          arbitrary[EORINumber],
          arbitrary[MovementId],
          arbitrary[MessageId],
          arbitrary[MovementReferenceNumber],
          Gen.oneOf(MessageType.arrivalRequestValues)
        ) {
          (eori, movementId, messageId, mrn, messageType) =>
            resetInternalAuth()

            val now: OffsetDateTime           = OffsetDateTime.now
            val generatedTime: OffsetDateTime = now.minusMinutes(1)

            val tempFile = SingletonTemporaryFileCreator.create()

            when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

            when(
              mockPersistenceService.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(MovementType.Arrival))
            )
              .thenReturn(
                EitherT.rightT[Future, MovementWithoutMessages](
                  MovementWithoutMessages(movementId, eori, None, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock), V2_1)
                )
              )

            when(
              mockPersistenceService.getSingleMessage(
                EORINumber(eqTo(eori.value)),
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                eqTo(MovementType.Arrival)
              )
            )
              .thenReturn(EitherT.rightT[Future, MessageResponse](MessageResponse(messageId, now, Some(messageType), None, Some(MessageStatus.Pending), None)))

            when(
              mockObjectStoreService
                .getObjectStoreFile(ObjectStoreResourceLocation(eqTo("movements/abcdef0123456789/abc.xml")))(any[ExecutionContext], any[HeaderCarrier])
            )
              .thenReturn(EitherT.rightT[Future, Source[ByteString, ?]](Source.empty[ByteString]))

            when(
              mockMovementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, ?]])
            )
              .thenReturn(EitherT.rightT[Future, Option[ExtractedData]](Some(ArrivalData(Some(eori), OffsetDateTime.now(clock), mrn))))

            when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, ?]], eqTo(messageType)))
              .thenReturn(EitherT.rightT[Future, MessageData](MessageData(generatedTime, Some(mrn))))

            when(
              mockPersistenceService.updateMessage(
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                argThat(
                  UpdateMessageDataMatcher(
                    Some(ObjectStoreURI("transit-movements/movements/abcdef0123456789/abc.xml")),
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
              .thenReturn(EitherT.rightT[Future, Unit](()))

            when(
              mockPersistenceService.updateMovement(
                MovementId(eqTo(movementId.value)),
                eqTo(Some(eori)),
                eqTo(Some(mrn)),
                eqTo(None),
                eqTo(None),
                any[OffsetDateTime]
              )
            )
              .thenReturn(EitherT.rightT[Future, Unit](()))

            val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
            val body    = Json.obj(
              "messageType"    -> messageType.code,
              "objectStoreURI" -> "transit-movements/movements/abcdef0123456789/abc.xml",
              "status"         -> "Success"
            )
            val request = FakeRequest(
              method = POST,
              uri = routes.MovementsController.updateMessage(eori, MovementType.Departure, movementId, messageId).url,
              headers = headers,
              body = body
            )

            val result: Future[Result] =
              controller.updateMessage(eori, MovementType.Arrival, movementId, messageId)(request)

            status(result) mustBe OK
            verify(mockPersistenceService, times(1)).updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              any[UpdateMessageData],
              any[OffsetDateTime]
            )
            verify(mockPersistenceService, times(1)).updateMovement(
              MovementId(eqTo(movementId.value)),
              eqTo(Some(eori)),
              eqTo(Some(mrn)),
              eqTo(None),
              eqTo(None),
              any[OffsetDateTime]
            )

            verify(mockInternalAuthActionProvider, times(1)).apply(
              eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
            )(any[ExecutionContext])
            verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }

        "if the movement EORI has already been set" in forAll(
          arbitrary[EORINumber],
          arbitrary[MovementId],
          arbitrary[MessageId],
          arbitrary[MovementType],
          arbitraryLRN.arbitrary,
          arbitraryMessageSender.arbitrary
        ) {
          (eori, movementId, messageId, movementType, lrn, messageSender) =>
            resetInternalAuth()
            val now: OffsetDateTime           = OffsetDateTime.now
            val generatedTime: OffsetDateTime = now.minusMinutes(1)
            val tempFile                      = SingletonTemporaryFileCreator.create()

            when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

            val messageType: MessageType = {
              if (movementType == MovementType.Departure) Gen.oneOf(MessageType.departureRequestValues)
              else Gen.oneOf(MessageType.arrivalRequestValues)
            }.sample.get
            when(
              mockPersistenceService.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(movementType))
            )
              .thenReturn(
                EitherT.rightT[Future, MovementWithoutMessages](
                  MovementWithoutMessages(movementId, eori, Some(eori), None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock), V2_1)
                )
              )

            when(
              mockObjectStoreService
                .getObjectStoreFile(ObjectStoreResourceLocation(eqTo("movements/abcdef0123456789/abc.xml")))(any[ExecutionContext], any[HeaderCarrier])
            )
              .thenReturn(EitherT.rightT[Future, Source[ByteString, ?]](Source.empty[ByteString]))

            when(
              mockPersistenceService.getSingleMessage(
                EORINumber(eqTo(eori.value)),
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                eqTo(movementType)
              )
            )
              .thenReturn(EitherT.rightT[Future, MessageResponse](MessageResponse(messageId, now, Some(messageType), None, Some(MessageStatus.Pending), None)))

            when(
              mockPersistenceService.updateMessage(
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                any[UpdateMessageData],
                any[OffsetDateTime]
              )
            )
              .thenReturn(EitherT.rightT[Future, Unit](()))

            when(
              mockMovementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, ?]])
            )
              .thenReturn(EitherT.rightT[Future, Option[ExtractedData]](Some(DeclarationData(Some(eori), OffsetDateTime.now(clock), lrn, messageSender))))

            when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, ?]], eqTo(messageType)))
              .thenReturn(EitherT.rightT[Future, MessageData](MessageData(generatedTime, None)))

            when(
              mockPersistenceService.updateMovement(
                MovementId(eqTo(movementId.value)),
                eqTo(None),
                eqTo(None),
                eqTo(Some(lrn)),
                eqTo(Some(messageSender)),
                any[OffsetDateTime]
              )
            )
              .thenReturn(EitherT.rightT[Future, Unit](()))

            val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
            val body    = Json.obj(
              "objectStoreURI" -> "transit-movements/movements/abcdef0123456789/abc.xml",
              "status"         -> "Success",
              "messageType"    -> messageType.code
            )
            val request = FakeRequest(
              method = POST,
              uri = routes.MovementsController.updateMessage(eori, movementType, movementId, messageId).url,
              headers = headers,
              body = body
            )

            val result: Future[Result] =
              controller.updateMessage(eori, movementType, movementId, messageId)(request)

            status(result) mustBe OK
            verify(mockPersistenceService, times(1)).updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              any[UpdateMessageData],
              any[OffsetDateTime]
            )
            verify(mockPersistenceService, times(1)).updateMovement(
              MovementId(eqTo(movementId.value)),
              eqTo(None),
              eqTo(None),
              eqTo(Some(lrn)),
              eqTo(Some(messageSender)),
              any[OffsetDateTime]
            )

            verify(mockInternalAuthActionProvider, times(1)).apply(
              eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
            )(any[ExecutionContext])
            verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }
      }

      "must return Bad Request" - {

        "if the object store URI is not a transit-movements owned URI" in forAll(
          arbitrary[EORINumber],
          arbitrary[MovementId],
          arbitrary[MessageId],
          arbitrary[MovementType],
          arbitraryLRN.arbitrary,
          arbitraryMessageSender.arbitrary
        ) {
          (eori, movementId, messageId, movementType, lrn, messageSender) =>
            resetInternalAuth()

            val now: OffsetDateTime           = OffsetDateTime.now
            val mrn: MovementReferenceNumber  = arbitraryMovementReferenceNumber.arbitrary.sample.get
            val generatedTime: OffsetDateTime = now.minusMinutes(1)
            val messageType: MessageType      = {
              if (movementType == MovementType.Departure) Gen.oneOf(MessageType.departureRequestValues)
              else Gen.oneOf(MessageType.arrivalRequestValues)
            }.sample.get

            when(
              mockPersistenceService.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(movementType))
            )
              .thenReturn(
                EitherT.rightT[Future, MovementWithoutMessages](
                  MovementWithoutMessages(movementId, eori, None, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock), V2_1)
                )
              )

            when(
              mockPersistenceService.getSingleMessage(
                EORINumber(eqTo(eori.value)),
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                eqTo(movementType)
              )
            )
              .thenReturn(EitherT.rightT[Future, MessageResponse](MessageResponse(messageId, now, Some(messageType), None, Some(MessageStatus.Pending), None)))

            when(
              mockObjectStoreService
                .getObjectStoreFile(ObjectStoreResourceLocation(eqTo("movements/abcdef0123456789/abc.xml")))(any[ExecutionContext], any[HeaderCarrier])
            )
              .thenReturn(EitherT.rightT[Future, Source[ByteString, ?]](Source.empty[ByteString]))

            when(
              mockMovementsXmlParsingService.extractData(eqTo(messageType), any[Source[ByteString, ?]])
            )
              .thenReturn(EitherT.rightT[Future, Option[ExtractedData]](Some(DeclarationData(Some(eori), OffsetDateTime.now(clock), lrn, messageSender))))

            when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, ?]], eqTo(messageType)))
              .thenReturn(EitherT.rightT[Future, MessageData](MessageData(generatedTime, Some(mrn))))

            when(
              mockPersistenceService.updateMessage(
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                any[UpdateMessageData],
                any[OffsetDateTime]
              )
            )
              .thenReturn(EitherT.rightT[Future, Unit](()))

            when(
              mockPersistenceService.updateMovement(
                MovementId(eqTo(movementId.value)),
                eqTo(Some(eori)),
                eqTo(None),
                eqTo(Some(lrn)),
                eqTo(Some(messageSender)),
                any[OffsetDateTime]
              )
            )
              .thenReturn(EitherT.rightT[Future, Unit](()))

            val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
            val body    = Json.obj(
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

            val result: Future[Result] =
              controller.updateMessage(eori, movementType, movementId, messageId)(request)

            status(result) mustBe BAD_REQUEST
            contentAsJson(result) mustBe Json.obj(
              "code"    -> "BAD_REQUEST",
              "message" -> "Provided Object Store URI is not owned by transit-movements"
            )
            verify(mockPersistenceService, times(0)).updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              any[UpdateMessageData],
              any[OffsetDateTime]
            )
            verify(mockPersistenceService, times(0)).updateMovement(
              MovementId(eqTo(movementId.value)),
              eqTo(Some(eori)),
              eqTo(None),
              eqTo(Some(lrn)),
              eqTo(Some(messageSender)),
              any[OffsetDateTime]
            )

            verify(mockInternalAuthActionProvider, times(1)).apply(
              eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
            )(any[ExecutionContext])
            verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }
      }

      "must return OK, if the update message is successful, given only status is provided in the request" in forAll(
        arbitrary[EORINumber],
        arbitrary[MovementType],
        arbitrary[MessageStatus],
        arbitrary[MessageType]
      ) {
        (eori, movementType, messageStatus, messageType) =>
          resetInternalAuth()
          reset(mockPersistenceService) // needed thanks to the generators running the test multiple times.
          val expectedUpdateData = UpdateMessageData(status = messageStatus)

          val now: OffsetDateTime = OffsetDateTime.now

          when(
            mockPersistenceService.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(movementType))
          )
            .thenReturn(
              EitherT.rightT[Future, MovementWithoutMessages](
                MovementWithoutMessages(movementId, eori, Some(eori), None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock), V2_1)
              )
            )

          when(
            mockPersistenceService.getSingleMessage(
              EORINumber(eqTo(eori.value)),
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              eqTo(movementType)
            )
          )
            .thenReturn(EitherT.rightT[Future, MessageResponse](MessageResponse(messageId, now, Some(messageType), None, Some(MessageStatus.Pending), None)))

          when(
            mockPersistenceService.updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              eqTo(expectedUpdateData),
              any[OffsetDateTime]
            )
          )
            .thenReturn(EitherT.rightT[Future, Unit](()))

          val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
          val body    = Json.obj(
            "status" -> messageStatus.toString
          )
          val request = FakeRequest(
            method = POST,
            uri = routes.MovementsController.updateMessage(eori, movementType, movementId, messageId).url,
            headers = headers,
            body = body
          )

          val result: Future[Result] =
            controller.updateMessage(eori, movementType, movementId, messageId)(request)

          status(result) mustBe OK
          verify(mockPersistenceService, times(1)).updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            any[UpdateMessageData],
            any[OffsetDateTime]
          )
          verify(mockPersistenceService, times(0)).updateMovement(
            MovementId(eqTo(movementId.value)),
            eqTo(Some(eori)),
            eqTo(None),
            eqTo(None),
            eqTo(None),
            any[OffsetDateTime]
          )

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
      }

      "must return BAD_REQUEST when JSON data extraction fails" in forAll(arbitrary[EORINumber], arbitrary[MovementType]) {
        (eori, messageType) =>
          resetInternalAuth()
          val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
          val body    = Json.obj(
            "objectStoreURI" -> "transit-movements/something.xml"
          )

          val request = FakeRequest(
            method = POST,
            uri = routes.MovementsController.updateMessage(eori, messageType, movementId, messageId).url,
            headers = headers,
            body = body
          )

          val result: Future[Result] =
            controller.updateMessage(eori, messageType, movementId, messageId)(request)

          status(result) mustBe BAD_REQUEST
          contentAsJson(result) mustBe Json.obj(
            "code"    -> "BAD_REQUEST",
            "message" -> "Could not parse the request"
          )
          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
      }

      "must return BAD REQUEST, if the update message is unsuccessful, given invalid status is provided in the request" in forAll(
        arbitrary[EORINumber],
        arbitrary[MovementType]
      ) {
        (eori, messageType) =>
          resetInternalAuth()

          when(
            mockPersistenceService.updateMessage(
              any[String].asInstanceOf[MovementId],
              any[String].asInstanceOf[MessageId],
              any[UpdateMessageData],
              any[OffsetDateTime]
            )
          )
            .thenReturn(EitherT.rightT[Future, Unit](()))

          val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
          val body    = Json.obj(
            "status" -> "123"
          )
          val request = FakeRequest(
            method = POST,
            uri = routes.MovementsController.updateMessage(eori, messageType, movementId, messageId).url,
            headers = headers,
            body = body
          )

          val result: Future[Result] =
            controller.updateMessage(eori, messageType, movementId, messageId)(request)

          status(result) mustBe BAD_REQUEST

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
      }

      "must return OK if successful given both MessageType and status" - {

        "if the message is a departure message and the first one in the movement" in forAll(
          arbitrary[EORINumber],
          arbitrary[MovementId],
          arbitrary[MessageId],
          arbitraryLRN.arbitrary,
          arbitraryMessageSender.arbitrary
        ) {
          (eori, movementId, messageId, lrn, messageSender) =>
            resetInternalAuth()
            val movementType                  = MovementType.Departure
            val now: OffsetDateTime           = OffsetDateTime.now
            val generatedTime: OffsetDateTime = now.minusMinutes(1)
            val mrn: MovementReferenceNumber  = arbitraryMovementReferenceNumber.arbitrary.sample.get

            when(
              mockPersistenceService.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(MovementType.Departure))
            )
              .thenReturn(
                EitherT.rightT[Future, MovementWithoutMessages](
                  MovementWithoutMessages(movementId, eori, None, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock), V2_1)
                )
              )

            when(
              mockPersistenceService.getSingleMessage(
                EORINumber(eqTo(eori.value)),
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                eqTo(movementType)
              )
            )
              .thenReturn(EitherT.rightT[Future, MessageResponse](MessageResponse(messageId, now, None, None, Some(MessageStatus.Pending), None)))

            when(
              mockMovementsXmlParsingService.extractData(eqTo(MovementType.Departure), any[Source[ByteString, ?]])
            )
              .thenReturn(EitherT.rightT[Future, DeclarationData](DeclarationData(Some(eori), OffsetDateTime.now(clock), lrn, messageSender)))

            when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, ?]], eqTo(MessageType.DeclarationData)))
              .thenReturn(EitherT.rightT[Future, MessageData](MessageData(generatedTime, Some(mrn))))

            when(
              mockPersistenceService.updateMessage(
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                any[UpdateMessageData],
                any[OffsetDateTime]
              )
            )
              .thenReturn(EitherT.rightT[Future, Unit](()))

            val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
            val body    = Json.obj(
              "status"      -> "Success",
              "messageType" -> "IE015"
            )
            val request = FakeRequest(
              method = POST,
              uri = routes.MovementsController.updateMessage(eori, MovementType.Arrival, movementId, messageId).url,
              headers = headers,
              body = body
            )

            val result: Future[Result] = controller.updateMessage(eori, MovementType.Departure, movementId, messageId)(request)

            status(result) mustBe OK
            verify(mockPersistenceService, times(1)).updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              any[UpdateMessageData],
              any[OffsetDateTime]
            )
            verify(mockPersistenceService, times(0)).updateMovement(
              MovementId(eqTo(movementId.value)),
              eqTo(Some(eori)),
              eqTo(None),
              eqTo(Some(lrn)),
              eqTo(Some(messageSender)),
              any[OffsetDateTime]
            )

            verify(mockInternalAuthActionProvider, times(1)).apply(
              eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
            )(any[ExecutionContext])
            verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }

        "if the message is an arrival message and the first one in the movement" in forAll(
          arbitrary[EORINumber],
          arbitrary[MovementId],
          arbitrary[MessageId],
          arbitrary[MovementReferenceNumber]
        ) {
          (eori, movementId, messageId, mrn) =>
            resetInternalAuth()
            val movementType        = MovementType.Arrival
            val messageType         = MessageType.ArrivalNotification
            val now: OffsetDateTime = OffsetDateTime.now

            when(
              mockPersistenceService.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(MovementType.Arrival))
            )
              .thenReturn(
                EitherT.rightT[Future, MovementWithoutMessages](
                  MovementWithoutMessages(movementId, eori, None, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock), V2_1)
                )
              )

            when(
              mockPersistenceService.getSingleMessage(
                EORINumber(eqTo(eori.value)),
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                eqTo(movementType)
              )
            )
              .thenReturn(EitherT.rightT[Future, MessageResponse](MessageResponse(messageId, now, Some(messageType), None, Some(MessageStatus.Pending), None)))

            when(
              mockPersistenceService.updateMessage(
                MovementId(eqTo(movementId.value)),
                MessageId(eqTo(messageId.value)),
                any[UpdateMessageData],
                any[OffsetDateTime]
              )
            )
              .thenReturn(EitherT.rightT[Future, Unit](()))

            val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
            val body    = Json.obj(
              "status"      -> "Success",
              "messageType" -> "IE007"
            )
            val request = FakeRequest(
              method = POST,
              uri = routes.MovementsController.updateMessage(eori, MovementType.Departure, movementId, messageId).url,
              headers = headers,
              body = body
            )

            val result: Future[Result] =
              controller.updateMessage(eori, MovementType.Arrival, movementId, messageId)(request)

            status(result) mustBe OK
            verify(mockPersistenceService, times(1)).updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              any[UpdateMessageData],
              any[OffsetDateTime]
            )
            verify(mockPersistenceService, times(0)).updateMovement(
              MovementId(eqTo(movementId.value)),
              eqTo(Some(eori)),
              eqTo(Some(mrn)),
              eqTo(None),
              eqTo(None),
              any[OffsetDateTime]
            )

            verify(mockInternalAuthActionProvider, times(1)).apply(
              eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
            )(any[ExecutionContext])
            verifyNoMoreInteractions(mockInternalAuthActionProvider)
        }

      }

      "must return BAD_REQUEST given invalid messageType for departure message" in forAll(arbitrary[EORINumber]) {
        eori =>
          resetInternalAuth()
          val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
          val body    = Json.obj(
            "status"      -> "Success",
            "messageType" -> "IE007"
          )

          val request = FakeRequest(
            method = POST,
            uri = routes.MovementsController.updateMessage(eori, MovementType.Departure, movementId, messageId).url,
            headers = headers,
            body = body
          )

          val result: Future[Result] =
            controller.updateMessage(eori, MovementType.Departure, movementId, messageId)(request)

          status(result) mustBe BAD_REQUEST
          contentAsJson(result) mustBe Json.obj(
            "code"    -> "BAD_REQUEST",
            "message" -> "Invalid messageType value: ArrivalNotification"
          )

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
      }

      "must return BAD_REQUEST if the message type does not match the expected type, if one exists" in forAll(
        arbitrary[EORINumber],
        arbitrary[MovementId],
        arbitrary[MessageId],
        Gen.oneOf(MessageType.departureRequestValues),
        arbitraryLRN.arbitrary,
        arbitraryMessageSender.arbitrary
      ) {
        (eori, movementId, messageId, messageType, lrn, messageSender) =>
          resetInternalAuth()
          val movementType                  = MovementType.Departure
          val now: OffsetDateTime           = OffsetDateTime.now
          val generatedTime: OffsetDateTime = now.minusMinutes(1)
          val mrn: MovementReferenceNumber  = arbitraryMovementReferenceNumber.arbitrary.sample.get

          when(
            mockPersistenceService.getMovementWithoutMessages(EORINumber(eqTo(eori.value)), MovementId(eqTo(movementId.value)), eqTo(MovementType.Departure))
          )
            .thenReturn(
              EitherT.rightT[Future, MovementWithoutMessages](
                MovementWithoutMessages(movementId, eori, None, None, None, OffsetDateTime.now(clock), OffsetDateTime.now(clock), V2_1)
              )
            )

          when(
            mockPersistenceService.getSingleMessage(
              EORINumber(eqTo(eori.value)),
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              eqTo(movementType)
            )
          )
            .thenReturn(
              EitherT.rightT[Future, MessageResponse](
                MessageResponse(messageId, now, Some(MessageType.ArrivalNotification), None, Some(MessageStatus.Pending), None)
              )
            )

          when(
            mockMovementsXmlParsingService.extractData(eqTo(MovementType.Departure), any[Source[ByteString, ?]])
          )
            .thenReturn(EitherT.rightT[Future, ExtractedData](DeclarationData(Some(eori), OffsetDateTime.now(clock), lrn, messageSender)))

          when(mockMessagesXmlParsingService.extractMessageData(any[Source[ByteString, ?]], eqTo(MessageType.DeclarationData)))
            .thenReturn(EitherT.rightT[Future, MessageData](MessageData(generatedTime, Some(mrn))))

          when(
            mockPersistenceService.updateMessage(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              any[UpdateMessageData],
              any[OffsetDateTime]
            )
          )
            .thenReturn(EitherT.rightT[Future, Unit](()))

          val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
          val body    = Json.obj(
            "objectStoreURI" -> "transit-movements/test/abc.xml",
            "status"         -> "Success",
            "messageType"    -> messageType.code
          )
          val request = FakeRequest(
            method = POST,
            uri = routes.MovementsController.updateMessage(eori, MovementType.Departure, movementId, messageId).url,
            headers = headers,
            body = body
          )

          val result: Future[Result] = controller.updateMessage(eori, MovementType.Departure, movementId, messageId)(request)

          status(result) mustBe BAD_REQUEST
          contentAsJson(result) mustBe Json.obj(
            "code"    -> "BAD_REQUEST",
            "message" -> "Message type does not match"
          )
          verify(mockPersistenceService, times(0)).updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            any[UpdateMessageData],
            any[OffsetDateTime]
          )
          verify(mockPersistenceService, times(0)).updateMovement(
            MovementId(eqTo(movementId.value)),
            eqTo(Some(eori)),
            eqTo(None),
            eqTo(Some(lrn)),
            eqTo(Some(messageSender)),
            any[OffsetDateTime]
          )

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
      }

      "must return BAD_REQUEST given invalid messageType for arrival message" in forAll(arbitrary[EORINumber]) {
        eori =>
          resetInternalAuth()
          val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
          val body    = Json.obj(
            "status"      -> "Success",
            "messageType" -> "IE015"
          )

          val request = FakeRequest(
            method = POST,
            uri = routes.MovementsController.updateMessage(eori, MovementType.Arrival, movementId, messageId).url,
            headers = headers,
            body = body
          )

          val result: Future[Result] =
            controller.updateMessage(eori, MovementType.Arrival, movementId, messageId)(request)

          status(result) mustBe BAD_REQUEST
          contentAsJson(result) mustBe Json.obj(
            "code"    -> "BAD_REQUEST",
            "message" -> "Invalid messageType value: DeclarationData"
          )

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages")), IAAction("WRITE")))
          )(any[ExecutionContext])
          verifyNoMoreInteractions(mockInternalAuthActionProvider)
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
        resetInternalAuth()
        reset(mockPersistenceService) // needed thanks to the generators running the test multiple times.
        val expectedUpdateMessageMetadata = UpdateMessageData(status = messageStatus)

        when(
          mockPersistenceService.updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(expectedUpdateMessageMetadata),
            any[OffsetDateTime]
          )
        )
          .thenReturn(EitherT.rightT[Future, Unit](()))

        val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON, Constants.APIVersionHeaderKey -> APIVersionHeader.V2_1.value))
        val body    = Json.obj(
          "status" -> messageStatus.toString
        )
        val request = FakeRequest(
          method = POST,
          uri = routes.MovementsController.updateMessageStatus(movementId, messageId).url,
          headers = headers,
          body = body
        )

        val result: Future[Result] =
          controller.updateMessageStatus(movementId, messageId)(request)

        status(result) mustBe OK
        verify(mockPersistenceService, times(1)).updateMessage(
          MovementId(eqTo(movementId.value)),
          MessageId(eqTo(messageId.value)),
          any[UpdateMessageData],
          any[OffsetDateTime]
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages/status")), IAAction("WRITE")))
        )(any[ExecutionContext])
        verifyNoMoreInteractions(mockInternalAuthActionProvider)
    }

    "must return BAD_REQUEST when JSON data extraction fails" in forAll(arbitrary[MovementId], arbitrary[MessageId]) {
      (movementId, messageId) =>
        resetInternalAuth()
        val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
        val body    = Json.obj(
          "status" -> "Nope"
        )
        val request = FakeRequest(
          method = POST,
          uri = routes.MovementsController.updateMessageStatus(movementId, messageId).url,
          headers = headers,
          body = body
        )

        val result: Future[Result] =
          controller.updateMessageStatus(movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Could not parse the request"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages/status")), IAAction("WRITE")))
        )(any[ExecutionContext])
        verifyNoMoreInteractions(mockInternalAuthActionProvider)
    }

    "must return NOT_FOUND when an incorrect message is specified" in forAll(arbitrary[MovementId], arbitrary[MessageId], arbitrary[MessageStatus]) {
      (movementId, messageId, messageStatus) =>
        resetInternalAuth()
        reset(mockPersistenceService) // needed thanks to the generators running the test multiple times.
        val expectedUpdateMessageMetadata = UpdateMessageData(status = messageStatus)

        when(
          mockPersistenceService.updateMessage(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            eqTo(expectedUpdateMessageMetadata),
            any[OffsetDateTime]
          )
        )
          .thenReturn(EitherT.leftT[Future, Unit](MongoError.DocumentNotFound(s"No movement found with the given id: ${movementId.value}")))

        val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
        val body    = Json.obj(
          "status" -> messageStatus.toString
        )
        val request = FakeRequest(
          method = POST,
          uri = routes.MovementsController.updateMessageStatus(movementId, messageId).url,
          headers = headers,
          body = body
        )

        val result: Future[Result] =
          controller.updateMessageStatus(movementId, messageId)(request)

        status(result) mustBe NOT_FOUND
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "NOT_FOUND",
          "message" -> s"No movement found with the given id: ${movementId.value}"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements"), ResourceLocation("movements/messages/status")), IAAction("WRITE")))
        )(any[ExecutionContext])
        verifyNoMoreInteractions(mockInternalAuthActionProvider)
    }
  }

}
