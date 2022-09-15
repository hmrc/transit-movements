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
import org.mockito.ArgumentMatchers.{eq => eqTo}
import org.mockito.MockitoSugar.reset
import org.mockito.MockitoSugar.when
import org.scalatest.BeforeAndAfterEach
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
import play.api.libs.Files.SingletonTemporaryFileCreator
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.Json

import scala.concurrent.duration.DurationInt
import play.api.mvc.Request
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers.contentAsJson
import play.api.test.Helpers.status
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.http.HttpVerbs.POST
import uk.gov.hmrc.transitmovements.base.SpecBase
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.DeclarationData
import uk.gov.hmrc.transitmovements.models.Departure
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.DepartureWithoutMessages
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.formats.PresentationFormats
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse
import uk.gov.hmrc.transitmovements.repositories.DeparturesRepository
import uk.gov.hmrc.transitmovements.services.DepartureFactory
import uk.gov.hmrc.transitmovements.services.DeparturesXmlParsingService
import uk.gov.hmrc.transitmovements.services.MessageFactory
import uk.gov.hmrc.transitmovements.services.errors.MongoError
import uk.gov.hmrc.transitmovements.services.errors.MongoError.UnexpectedError
import uk.gov.hmrc.transitmovements.services.errors.ParseError
import uk.gov.hmrc.transitmovements.services.errors.StreamError

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import scala.concurrent.Future
import scala.xml.NodeSeq

class DeparturesControllerSpec
    extends SpecBase
    with TestActorSystem
    with Matchers
    with OptionValues
    with ScalaFutures
    with BeforeAndAfterEach
    with PresentationFormats
    with ModelGenerators {

  def fakeRequestDepartures[A](
    method: String,
    body: NodeSeq
  ): Request[NodeSeq] =
    FakeRequest(
      method = method,
      uri = routes.DeparturesController.createDeparture(eori).url,
      headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML)),
      body = body
    )

  implicit val timeout: Timeout = 5.seconds

  val mockXmlParsingService    = mock[DeparturesXmlParsingService]
  val mockRepository           = mock[DeparturesRepository]
  val mockDepartureFactory     = mock[DepartureFactory]
  val mockMessageFactory       = mock[MessageFactory]
  val mockTemporaryFileCreator = mock[TemporaryFileCreator]

  lazy val eori            = EORINumber("eori")
  lazy val declarationData = DeclarationData(eori, OffsetDateTime.now(ZoneId.of("UTC")))

  lazy val departureDataEither: EitherT[Future, ParseError, DeclarationData] =
    EitherT.rightT(declarationData)

  val now = OffsetDateTime.now

  lazy val departureId = DepartureId("ABC123")

  lazy val instant: OffsetDateTime = OffsetDateTime.of(2022, 3, 14, 1, 0, 0, 0, ZoneOffset.UTC)

  lazy val eoriNumber = EORINumber("A")

  lazy val messageId = MessageId("XYZ345")

  lazy val message = Message(
    messageId,
    now,
    now,
    MessageType.DeclarationData,
    None,
    None,
    None
  )

  lazy val departure = Departure(
    departureId,
    eoriNumber,
    eoriNumber,
    None,
    now,
    now,
    NonEmptyList.one(
      message
    )
  )

  override def afterEach() {
    reset(mockTemporaryFileCreator)
    reset(mockXmlParsingService)
    reset(mockDepartureFactory)
    super.afterEach()
  }

  val controller =
    new DeparturesController(
      stubControllerComponents(),
      mockDepartureFactory,
      mockMessageFactory,
      mockRepository,
      mockXmlParsingService,
      mockTemporaryFileCreator
    )

  "createDeparture" - {

    val validXml: NodeSeq =
      <CC015C>
        <messageSender>ABC123</messageSender>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
      </CC015C>

    lazy val messageFactoryEither: EitherT[Future, StreamError, Message] =
      EitherT.rightT(message)

    "must return OK if XML data extraction is successful" in {

      val tempFile = SingletonTemporaryFileCreator.create()
      when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

      when(mockXmlParsingService.extractDeclarationData(any[Source[ByteString, _]]))
        .thenReturn(departureDataEither)

      when(mockDepartureFactory.create(any[String].asInstanceOf[EORINumber], any[DeclarationData], any[Message]))
        .thenReturn(departure)

      when(mockMessageFactory.create(any[MessageType], any[OffsetDateTime], any[Option[MessageId]], any[Source[ByteString, Future[IOResult]]]))
        .thenReturn(messageFactoryEither)

      when(mockRepository.insert(any()))
        .thenReturn(EitherT.rightT(Right(())))

      val request = fakeRequestDepartures(POST, validXml)

      val result =
        controller.createDeparture(eori)(request)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj(
        "departureId" -> "ABC123",
        "messageId"   -> "XYZ345"
      )
    }

    "must return BAD_REQUEST when XML data extraction fails" - {

      "contains message to indicate element not found" in {

        val elementNotFoundXml: NodeSeq =
          <CC015C></CC015C>

        when(mockXmlParsingService.extractDeclarationData(any[Source[ByteString, _]]))
          .thenReturn(EitherT.leftT(ParseError.NoElementFound("messageSender")))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = fakeRequestDepartures(POST, elementNotFoundXml)

        val result =
          controller.createDeparture(eori)(request)

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

        when(mockXmlParsingService.extractDeclarationData(any[Source[ByteString, _]]))
          .thenReturn(EitherT.leftT(ParseError.TooManyElementsFound("messageSender")))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = fakeRequestDepartures(POST, tooManyFoundXml)

        val result =
          controller.createDeparture(eori)(request)

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

        when(mockXmlParsingService.extractDeclarationData(any[Source[ByteString, _]]))
          .thenReturn(
            EitherT.leftT(
              ParseError.BadDateTime("preparationDateAndTime", new DateTimeParseException("Text 'no' could not be parsed at index 0", cs, 0))
            )
          )

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = fakeRequestDepartures(POST, tooManyFoundXml)

        val result =
          controller.createDeparture(eori)(request)

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

        when(mockXmlParsingService.extractDeclarationData(any[Source[ByteString, _]]))
          .thenReturn(EitherT.leftT(ParseError.UnexpectedError(Some(new IllegalArgumentException()))))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = FakeRequest(
          method = POST,
          uri = routes.DeparturesController.createDeparture(eori).url,
          headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML)),
          body = unknownErrorXml
        )

        val result =
          controller.createDeparture(eori)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )
      }

      "when file creation fails" in {

        when(mockTemporaryFileCreator.create()).thenThrow(new Exception("File creation failed"))

        val request = fakeRequestDepartures(POST, validXml)

        val result =
          controller.createDeparture(eori)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )
      }
    }
  }

  "getDepartureWithoutMessages" - {
    val request = FakeRequest("GET", routes.DeparturesController.getDepartureWithoutMessages(eoriNumber, departureId).url)

    "must return OK if departure found" in {
      when(mockRepository.getDepartureWithoutMessages(EORINumber(any()), DepartureId(any())))
        .thenReturn(EitherT.rightT(Some(DepartureWithoutMessages.fromDeparture(departure))))

      val result = controller.getDepartureWithoutMessages(eoriNumber, departureId)(request)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.toJson(DepartureWithoutMessages.fromDeparture(departure))(PresentationFormats.departureWithoutMessagesFormat)
    }

    "must return NOT_FOUND if no departure found" in {
      when(mockRepository.getDepartureWithoutMessages(EORINumber(any()), DepartureId(any())))
        .thenReturn(EitherT.rightT(None))

      val result = controller.getDepartureWithoutMessages(eoriNumber, departureId)(request)

      status(result) mustBe NOT_FOUND
    }

    "must return INTERNAL_SERVER_ERROR if repository has an error" in {
      when(mockRepository.getDepartureWithoutMessages(EORINumber(any()), DepartureId(any())))
        .thenReturn(EitherT.leftT(MongoError.UnexpectedError(Some(new Throwable("test")))))

      val result = controller.getDepartureWithoutMessages(eoriNumber, departureId)(request)

      status(result) mustBe INTERNAL_SERVER_ERROR
    }
  }

  "getMessage" - {
    val request = FakeRequest("GET", routes.DeparturesController.getMessage(eoriNumber, departureId, messageId).url)

    "must return OK if message found in the correct format" in {
      val messageResponse = MessageResponse.fromMessageWithBody(departure.messages.head)

      when(mockRepository.getSingleMessage(EORINumber(any()), DepartureId(any()), MessageId(any())))
        .thenReturn(EitherT.rightT(Some(messageResponse)))

      val result = controller.getMessage(eoriNumber, departureId, messageId)(request)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.toJson(messageResponse)
    }

    "must return NOT_FOUND if no message found" in {
      when(mockRepository.getSingleMessage(EORINumber(any()), DepartureId(any()), MessageId(any())))
        .thenReturn(EitherT.rightT(None))

      val result = controller.getMessage(eoriNumber, departureId, messageId)(request)

      status(result) mustBe NOT_FOUND
    }

    "must return INTERNAL_SERVICE_ERROR when a database error is thrown" in {
      when(mockRepository.getSingleMessage(EORINumber(any()), DepartureId(any()), MessageId(any())))
        .thenReturn(EitherT.leftT(MongoError.UnexpectedError(Some(new Throwable("test")))))

      val result = controller.getMessage(eoriNumber, departureId, messageId)(request)

      status(result) mustBe INTERNAL_SERVER_ERROR
    }
  }

  "getDepartureMessages" - {

    val request = FakeRequest("GET", routes.DeparturesController.getDepartureWithoutMessages(eoriNumber, departureId).url)

    "must return OK and a list of message ids" in {
      val messageResponses = MessageResponse.fromMessageWithoutBody(departure.messages.head)

      lazy val messageResponseList = NonEmptyList.one(messageResponses)

      when(mockRepository.getMessages(EORINumber(any()), DepartureId(any()), eqTo(None)))
        .thenReturn(EitherT.rightT(Some(NonEmptyList.one(messageResponses))))

      val result = controller.getDepartureMessages(eoriNumber, departureId, None)(request)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.toJson(messageResponseList)
    }

    "must return NOT_FOUND if no departure found" in {
      when(mockRepository.getMessages(EORINumber(any()), DepartureId(any()), eqTo(None)))
        .thenReturn(EitherT.rightT(None))

      val result = controller.getDepartureMessages(eoriNumber, departureId, None)(request)

      status(result) mustBe NOT_FOUND
    }

    "must return INTERNAL_SERVER_ERROR when a database error is thrown" in {
      when(mockRepository.getMessages(EORINumber(any()), DepartureId(any()), eqTo(None)))
        .thenReturn(EitherT.leftT(UnexpectedError(None)))

      val result = controller.getDepartureMessages(eoriNumber, departureId, None)(request)

      status(result) mustBe INTERNAL_SERVER_ERROR
    }
  }

  "getDeparturesForEori" - {
    val request = FakeRequest("GET", routes.DeparturesController.getDeparturesForEori(eoriNumber).url)

    "must return OK if departures were found" in {
      val response = DepartureWithoutMessages.fromDeparture(departure)

      when(mockRepository.getDepartures(EORINumber(any())))
        .thenReturn(EitherT.rightT(Some(NonEmptyList(response, List.empty))))

      val result = controller.getDeparturesForEori(eoriNumber)(request)
      status(result) mustBe OK
      contentAsJson(result) mustBe Json.toJson(NonEmptyList(response, List.empty))
    }

    "must return NOT_FOUND if no ids were found" in {
      when(mockRepository.getDepartures(EORINumber(any())))
        .thenReturn(EitherT.rightT(None))

      val result = controller.getDeparturesForEori(eoriNumber)(request)

      status(result) mustBe NOT_FOUND
    }

    "must return INTERNAL_SERVICE_ERROR when a database error is thrown" in {
      when(mockRepository.getDepartures(EORINumber(any())))
        .thenReturn(EitherT.leftT(MongoError.UnexpectedError(Some(new Throwable("test")))))

      val result = controller.getDeparturesForEori(eoriNumber)(request)

      status(result) mustBe INTERNAL_SERVER_ERROR
    }
  }

}
