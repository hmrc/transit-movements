/*
 * Copyright 2022 HM Revenue & Customs
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
import cats.data.NonEmptyList
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar.reset
import org.mockito.MockitoSugar.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.BeforeAndAfterEach
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.OK
import play.api.libs.Files.SingletonTemporaryFileCreator
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.Json
import play.api.mvc.Request

import scala.concurrent.duration.DurationInt
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers.contentAsJson
import play.api.test.Helpers.status
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.http.HttpVerbs.POST
import uk.gov.hmrc.transitmovements.base.SpecBase
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.ArrivalData
import uk.gov.hmrc.transitmovements.models.ArrivalId
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.Movement
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.MovementType
import uk.gov.hmrc.transitmovements.models.formats.PresentationFormats
import uk.gov.hmrc.transitmovements.repositories.MovementsRepository
import uk.gov.hmrc.transitmovements.services.MessageFactory
import uk.gov.hmrc.transitmovements.services.MovementFactory
import uk.gov.hmrc.transitmovements.services.MovementsXmlParsingService
import uk.gov.hmrc.transitmovements.services.errors.ParseError
import uk.gov.hmrc.transitmovements.services.errors.StreamError

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import scala.concurrent.Future
import scala.xml.NodeSeq

class ArrivalsControllerSpec
    extends SpecBase
    with TestActorSystem
    with Matchers
    with OptionValues
    with ScalaFutures
    with BeforeAndAfterEach
    with PresentationFormats
    with ModelGenerators {

  implicit val timeout: Timeout = 5.seconds

  val mockXmlParsingService    = mock[MovementsXmlParsingService]
  val mockRepository           = mock[MovementsRepository]
  val mockMovementFactory      = mock[MovementFactory]
  val mockMessageFactory       = mock[MessageFactory]
  val mockTemporaryFileCreator = mock[TemporaryFileCreator]

  lazy val now                     = OffsetDateTime.now
  lazy val instant: OffsetDateTime = OffsetDateTime.of(2022, 3, 14, 1, 0, 0, 0, ZoneOffset.UTC)

  lazy val eoriNumber              = arbitrary[EORINumber].sample.get
  lazy val movementId              = arbitrary[MovementId].sample.get
  lazy val arrivalId               = arbitrary[ArrivalId].sample.get
  lazy val messageId               = arbitrary[MessageId].sample.get
  lazy val movementReferenceNumber = arbitrary[MovementReferenceNumber].sample.get

  lazy val arrivalData = ArrivalData(eoriNumber, OffsetDateTime.now(ZoneId.of("UTC")), movementReferenceNumber)

  lazy val arrivalDataEither: EitherT[Future, ParseError, ArrivalData] =
    EitherT.rightT(arrivalData)

  lazy val message = arbitrary[Message].sample.value.copy(id = messageId, generated = now, received = now, messageType = MessageType.ArrivalNotification)

  lazy val movement = arbitrary[Movement].sample.value.copy(
    _id = movementId,
    enrollmentEORINumber = eoriNumber,
    movementEORINumber = eoriNumber,
    movementReferenceNumber = Some(movementReferenceNumber),
    created = now,
    updated = now,
    messages = NonEmptyList.one(message)
  )

  def fakeRequestArrivals[A](
    method: String,
    body: NodeSeq
  ): Request[NodeSeq] =
    FakeRequest(
      method = method,
      uri = routes.ArrivalsController.createArrival(eoriNumber).url,
      headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML)),
      body = body
    )

  val controller =
    new ArrivalsController(
      stubControllerComponents(),
      mockMovementFactory,
      mockMessageFactory,
      mockRepository,
      mockXmlParsingService,
      mockTemporaryFileCreator
    )

  override def afterEach() {
    reset(mockTemporaryFileCreator)
    reset(mockXmlParsingService)
    reset(mockMovementFactory)
    super.afterEach()
  }

  "createArrival" - {

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

    lazy val messageFactoryEither: EitherT[Future, StreamError, Message] =
      EitherT.rightT(message)

    "must return OK if XML data extraction is successful" in {

      val tempFile = SingletonTemporaryFileCreator.create()
      when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

      when(mockXmlParsingService.extractArrivalData(any[Source[ByteString, _]]))
        .thenReturn(arrivalDataEither)

      when(
        mockMovementFactory.createArrival(any[String].asInstanceOf[EORINumber], any[String].asInstanceOf[MovementType], any[ArrivalData], any[Message])
      )
        .thenReturn(movement)

      when(mockMessageFactory.create(any[MessageType], any[OffsetDateTime], any[Option[MessageId]], any[Source[ByteString, Future[IOResult]]]))
        .thenReturn(messageFactoryEither)

      when(mockRepository.insert(any()))
        .thenReturn(EitherT.rightT(Right(())))

      val request = fakeRequestArrivals(POST, validXml)

      val result =
        controller.createArrival(eoriNumber)(request)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj(
        "arrivalId" -> movementId.value,
        "messageId" -> messageId.value
      )
    }

    "must return BAD_REQUEST when XML data extraction fails" - {

      "contains message to indicate element not found" in {

        val elementNotFoundXml: NodeSeq =
          <CC007C></CC007C>

        when(mockXmlParsingService.extractArrivalData(any[Source[ByteString, _]]))
          .thenReturn(EitherT.leftT(ParseError.NoElementFound("messageSender")))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = fakeRequestArrivals(POST, elementNotFoundXml)

        val result =
          controller.createArrival(eoriNumber)(request)

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

        when(mockXmlParsingService.extractArrivalData(any[Source[ByteString, _]]))
          .thenReturn(EitherT.leftT(ParseError.TooManyElementsFound("messageSender")))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = fakeRequestArrivals(POST, tooManyFoundXml)

        val result =
          controller.createArrival(eoriNumber)(request)

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

        when(mockXmlParsingService.extractArrivalData(any[Source[ByteString, _]]))
          .thenReturn(
            EitherT.leftT(
              ParseError.BadDateTime("preparationDateAndTime", new DateTimeParseException("Text 'no' could not be parsed at index 0", cs, 0))
            )
          )

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = fakeRequestArrivals(POST, tooManyFoundXml)

        val result =
          controller.createArrival(eoriNumber)(request)

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

        when(mockXmlParsingService.extractArrivalData(any[Source[ByteString, _]]))
          .thenReturn(EitherT.leftT(ParseError.UnexpectedError(Some(new IllegalArgumentException()))))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = FakeRequest(
          method = POST,
          uri = routes.ArrivalsController.createArrival(eoriNumber).url,
          headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML)),
          body = unknownErrorXml
        )

        val result =
          controller.createArrival(eoriNumber)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )
      }

      "when file creation fails" in {

        when(mockTemporaryFileCreator.create()).thenThrow(new Exception("File creation failed"))

        val request = fakeRequestArrivals(POST, validXml)

        val result =
          controller.createArrival(eoriNumber)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )
      }
    }
  }

}
