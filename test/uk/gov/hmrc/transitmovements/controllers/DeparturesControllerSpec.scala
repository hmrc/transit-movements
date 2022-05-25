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

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.util.Timeout
import org.mockito.scalatest.MockitoSugar
import org.scalatest.BeforeAndAfterEach
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.http.HeaderNames
import play.api.http.Status.ACCEPTED
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.Json

import scala.concurrent.duration.DurationInt
import play.api.mvc.ControllerComponents
import play.api.mvc.Request
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers.contentAsJson
import play.api.test.Helpers.status
import uk.gov.hmrc.http.HttpVerbs.POST
import uk.gov.hmrc.transitmovements.services.DeparturesXmlParsingService

import java.nio.charset.StandardCharsets
import scala.xml.NodeSeq

class DeparturesControllerSpec
    extends AnyFreeSpec
    with Matchers
    with GuiceOneAppPerSuite
    with OptionValues
    with ScalaFutures
    with MockitoSugar
    with BeforeAndAfterEach {

  val validXml: NodeSeq =
    <CC015C>
      <messageSender>ABC123</messageSender>
      <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
    </CC015C>

  private def createStream(node: NodeSeq): Source[ByteString, _] = createStream(node.mkString)

  private def createStream(string: String): Source[ByteString, _] =
    Source.single(ByteString(string, StandardCharsets.UTF_8))

  def fakeRequestDepartures[A](
    method: String,
    body: NodeSeq
  ): Request[Source[ByteString, _]] =
    FakeRequest(
      method = method,
      uri = routes.DeparturesController.post().url,
      headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> "application/xml")),
      body = createStream(body)
    )

  implicit val timeout: Timeout = 5.seconds

  override lazy val app: Application = GuiceApplicationBuilder().build()

  implicit lazy val materializer: Materializer = app.materializer

  "/POST" - {

    "must return ACCEPTED if XML data extraction is successful" in {

      val request = fakeRequestDepartures(POST, validXml)

      val result = app.injector.instanceOf[DeparturesController].post()(request)

      status(result) mustBe ACCEPTED
    }

    "must return BAD_REQUEST when XML data extraction fails" - {

      "contains message to indicate element not found" in {

        val elementNotFoundXml: NodeSeq =
          <CC015C></CC015C>

        val request = fakeRequestDepartures(POST, elementNotFoundXml)

        val result = app.injector.instanceOf[DeparturesController].post()(request)

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

        val request = fakeRequestDepartures(POST, tooManyFoundXml)

        val result = app.injector.instanceOf[DeparturesController].post()(request)

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

        val request = fakeRequestDepartures(POST, tooManyFoundXml)

        val result = app.injector.instanceOf[DeparturesController].post()(request)

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

        val request = FakeRequest(
          method = POST,
          uri = routes.DeparturesController.post().url,
          headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> "application/xml")),
          body = createStream(unknownErrorXml)
        )

        val result = app.injector.instanceOf[DeparturesController].post()(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )
      }

      "when file creation fails" in {

        val mockXmlParsingService    = mock[DeparturesXmlParsingService]
        val mockTemporaryFileCreator = mock[TemporaryFileCreator]

        val sut = new DeparturesController(app.injector.instanceOf[ControllerComponents], mockXmlParsingService, mockTemporaryFileCreator)(app.materializer)

        when(mockTemporaryFileCreator.create()).thenThrow(new Exception("File creation failed"))

        val request = fakeRequestDepartures(POST, validXml)

        val result = sut.post()(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )
      }
    }
  }
}
