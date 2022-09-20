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
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar.reset
import org.mockito.MockitoSugar.when
import org.scalatest.BeforeAndAfterEach
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
import play.api.libs.Files.SingletonTemporaryFileCreator
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.Json
import play.api.mvc.Headers
import play.api.mvc.Request
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers.contentAsJson
import play.api.test.Helpers.status
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.http.HttpVerbs.POST
import uk.gov.hmrc.transitmovements.base.SpecBase
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.controllers.errors.HeaderExtractError.InvalidMessageType
import uk.gov.hmrc.transitmovements.controllers.errors.HeaderExtractError.NoHeaderFound
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageData
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.formats.PresentationFormats
import uk.gov.hmrc.transitmovements.repositories.DeparturesRepository
import uk.gov.hmrc.transitmovements.services.MessageFactory
import uk.gov.hmrc.transitmovements.services.MessagesXmlParsingService
import uk.gov.hmrc.transitmovements.services.errors.MongoError
import uk.gov.hmrc.transitmovements.services.errors.ParseError
import uk.gov.hmrc.transitmovements.services.errors.StreamError

import scala.concurrent.ExecutionContext.Implicits.global
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.xml.NodeSeq

class MovementsControllerSpec
    extends SpecBase
    with TestActorSystem
    with Matchers
    with OptionValues
    with ScalaFutures
    with BeforeAndAfterEach
    with PresentationFormats
    with ModelGenerators {

  val mrn = arbitraryMovementReferenceNumber.arbitrary.sample.get

  val messageType = MessageType.DeclarationData

  implicit val timeout: Timeout = 5.seconds

  val movementId = arbitraryMovementId.arbitrary.sample.get
  val messageId  = arbitraryMessageId.arbitrary.sample.get
  val triggerId  = arbitraryMessageId.arbitrary.sample.get

  val mockXmlParsingService          = mock[MessagesXmlParsingService]
  val mockRepository                 = mock[DeparturesRepository]
  val mockMessageFactory             = mock[MessageFactory]
  val mockTemporaryFileCreator       = mock[TemporaryFileCreator]
  val mockMessageTypeHeaderExtractor = mock[MessageTypeHeaderExtractor]

  lazy val messageData: MessageData = MessageData(OffsetDateTime.now(ZoneId.of("UTC")), None)

  lazy val messageDataEither: EitherT[Future, ParseError, MessageData] =
    EitherT.rightT(messageData)

  val now = OffsetDateTime.now

  lazy val message = arbitraryMessage.arbitrary.sample.get.copy(id = messageId, generated = now, received = now, triggerId = Some(triggerId))

  def fakeRequest[A](
    method: String,
    body: NodeSeq,
    headers: FakeHeaders = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML, "X-Message-Type" -> messageType.code))
  ): Request[NodeSeq] =
    FakeRequest(
      method = method,
      uri = routes.MovementsController.updateMovement(movementId, triggerId).url,
      headers = headers,
      body = body
    )

  override def afterEach() {
    reset(mockTemporaryFileCreator)
    reset(mockXmlParsingService)
    reset(mockMessageFactory)
    super.afterEach()
  }

  val controller =
    new MovementsController(stubControllerComponents(), mockMessageFactory, mockRepository, mockXmlParsingService, mockTemporaryFileCreator)

  "updateMovement" - {

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

      when(mockMessageTypeHeaderExtractor.extract(any[Headers]))
        .thenReturn(EitherT.rightT(messageType))

      when(mockXmlParsingService.extractMessageData(any[Source[ByteString, _]], any[MessageType]))
        .thenReturn(messageDataEither)

      when(mockMessageFactory.create(any[MessageType], any[OffsetDateTime], any[Option[MessageId]], any[Source[ByteString, Future[IOResult]]]))
        .thenReturn(messageFactoryEither)

      when(mockRepository.updateMessages(any[String].asInstanceOf[DepartureId], any[Message], any[Option[MovementReferenceNumber]]))
        .thenReturn(EitherT.rightT(()))

      val request = fakeRequest(POST, validXml)

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

        when(mockMessageTypeHeaderExtractor.extract(any[Headers]))
          .thenReturn(EitherT.rightT(MessageType.InvalidationDecision))

        when(mockXmlParsingService.extractMessageData(any[Source[ByteString, _]], any[MessageType]))
          .thenReturn(
            EitherT.leftT(
              ParseError.BadDateTime("preparationDateAndTime", new DateTimeParseException("Text 'invalid' could not be parsed at index 0", cs, 0))
            )
          )

        when(mockMessageFactory.create(any[MessageType], any[OffsetDateTime], any[Option[MessageId]], any[Source[ByteString, Future[IOResult]]]))
          .thenReturn(messageFactoryEither)

        when(mockRepository.updateMessages(any[String].asInstanceOf[DepartureId], any[Message], any[Option[MovementReferenceNumber]]))
          .thenReturn(EitherT.rightT(()))

        val request = fakeRequest(POST, xml)

        val result =
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

        when(mockMessageTypeHeaderExtractor.extract(any[Headers]))
          .thenReturn(EitherT.rightT(messageType))

        when(mockXmlParsingService.extractMessageData(any[Source[ByteString, _]], any[MessageType]))
          .thenReturn(messageDataEither)

        when(mockMessageFactory.create(any[MessageType], any[OffsetDateTime], any[Option[MessageId]], any[Source[ByteString, Future[IOResult]]]))
          .thenReturn(messageFactoryEither)

        when(mockRepository.updateMessages(any[String].asInstanceOf[DepartureId], any[Message], any[Option[MovementReferenceNumber]]))
          .thenReturn(EitherT.leftT(MongoError.DocumentNotFound(s"No departure found with the given id: ${movementId.value}")))

        val request = fakeRequest(POST, validXml)

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

        when(mockMessageTypeHeaderExtractor.extract(any[Headers]))
          .thenReturn(EitherT.leftT(NoHeaderFound("Missing X-Message-Type header value")))

        val request = fakeRequest(POST, validXml, FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML)))

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

        when(mockMessageTypeHeaderExtractor.extract(any[Headers]))
          .thenReturn(EitherT.leftT(InvalidMessageType(s"Invalid X-Message-Type header value: invalid")))

        val request = fakeRequest(POST, validXml, FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML, "X-Message-Type" -> "invalid")))

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

        when(mockMessageTypeHeaderExtractor.extract(any[Headers]))
          .thenReturn(EitherT.rightT(MessageType.MrnAllocated))

        when(mockXmlParsingService.extractMessageData(any[Source[ByteString, _]], any[MessageType]))
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

        val request = fakeRequest(POST, validXml)

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

}
