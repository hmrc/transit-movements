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

import scala.concurrent.ExecutionContext.Implicits.global
import play.api.http.HeaderNames
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.INTERNAL_SERVER_ERROR
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
import uk.gov.hmrc.transitmovements.models.DeclarationData
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.MovementMessageId
import uk.gov.hmrc.transitmovements.models.responses.DeclarationResponse
import uk.gov.hmrc.transitmovements.repositories.DeparturesRepository
import uk.gov.hmrc.transitmovements.services.DeparturesService
import uk.gov.hmrc.transitmovements.services.DeparturesXmlParsingService
import uk.gov.hmrc.transitmovements.services.errors.MongoError
import uk.gov.hmrc.transitmovements.services.errors.ParseError

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import scala.concurrent.Future
import scala.xml.NodeSeq

class DeparturesControllerSpec extends TestActorSystem with SpecBase with Matchers with OptionValues with ScalaFutures with BeforeAndAfterEach {

  def fakeRequestDepartures[A](
    method: String,
    body: NodeSeq
  ): Request[NodeSeq] =
    FakeRequest(
      method = method,
      uri = routes.DeparturesController.post(eori).url,
      headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> "app/xml")),
      body = body
    )

  implicit val timeout: Timeout = 5.seconds

  val mockXmlParsingService    = mock[DeparturesXmlParsingService]
  val repository               = mock[DeparturesRepository]
  val mockDeparturesService    = mock[DeparturesService]
  val mockTemporaryFileCreator = mock[TemporaryFileCreator]

  lazy val eori            = EORINumber("eori")
  lazy val declarationData = DeclarationData(eori, OffsetDateTime.now(ZoneId.of("UTC")))

  lazy val departureDataEither: EitherT[Future, ParseError, DeclarationData] =
    EitherT.rightT(declarationData)

  lazy val declarationResponseEither: EitherT[Future, MongoError, DeclarationResponse] =
    EitherT.rightT(DeclarationResponse(DepartureId("ABC123"), MovementMessageId("XYZ345")))

  override def afterEach() {
    reset(mockTemporaryFileCreator)
    reset(mockXmlParsingService)
    reset(mockDeparturesService)
    super.afterEach()
  }

  val controller =
    new DeparturesController(stubControllerComponents(), mockDeparturesService, mockXmlParsingService, mockTemporaryFileCreator)

  "/POST" - {

    val validXml: NodeSeq =
      <CC015C>
        <messageSender>ABC123</messageSender>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
      </CC015C>

    "must return OK if XML data extraction is successful" in {

      val tempFile = SingletonTemporaryFileCreator.create()
      when(mockTemporaryFileCreator.create()).thenReturn(tempFile)

      when(mockXmlParsingService.extractDeclarationData(any[Source[ByteString, _]]))
        .thenReturn(departureDataEither)

      when(mockDeparturesService.create(any[String].asInstanceOf[EORINumber], any[DeclarationData], any[Source[ByteString, Future[IOResult]]]))
        .thenReturn(declarationResponseEither)

      val request = fakeRequestDepartures(POST, validXml)

      val result =
        controller.post(eori)(request)

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
          controller.post(eori)(request)

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
          controller.post(eori)(request)

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
          controller.post(eori)(request)

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
          .thenReturn(EitherT.leftT(ParseError.Unknown(Some(new IllegalArgumentException()))))

        when(mockTemporaryFileCreator.create()).thenReturn(SingletonTemporaryFileCreator.create())

        val request = FakeRequest(
          method = POST,
          uri = routes.DeparturesController.post(eori).url,
          headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> "app/xml")),
          body = unknownErrorXml
        )

        val result =
          controller.post(eori)(request)

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
          controller.post(eori)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )
      }
    }
  }
}
