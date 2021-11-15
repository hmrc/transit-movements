/*
 * Copyright 2021 HM Revenue & Customs
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

package controllers

import akka.stream.scaladsl.Source
import akka.util.ByteString
import base.TestActorSystem
import cats.data.NonEmptyList
import config.Constants
import models.MessageType
import models.errors.BadRequestError
import models.errors.InternalServiceError
import models.errors.NotFoundError
import models.errors.SchemaValidationError
import models.errors.TransitMovementError
import models.errors.UpstreamServiceError
import models.errors.XmlValidationError
import models.values.DepartureId
import models.values.MessageId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.http.ContentTypes
import play.api.http.Status
import play.api.libs.json.JsString
import play.api.libs.json.Json
import play.api.mvc.PlayBodyParsers
import play.api.test.FakeRequest
import play.api.test.Helpers
import play.api.test.Helpers._
import services.FakeDeparturesService
import uk.gov.hmrc.http.UpstreamErrorResponse

import scala.concurrent.Future

class DeparturesControllerSpec extends AnyFlatSpec with Matchers with TestActorSystem {

  def controller(
    sendDeclarationResponse: () => Future[Either[TransitMovementError, DepartureId]] = () =>
      Future.failed(new NotImplementedError),
    receiveMessageResponse: () => Future[Either[TransitMovementError, MessageId]] = () =>
      Future.failed(new NotImplementedError)
  ) = new DeparturesController(
    FakeDeparturesService(sendDeclarationResponse, receiveMessageResponse),
    Helpers.stubControllerComponents(playBodyParsers = PlayBodyParsers())
  )

  "DeparturesController.sendDeclarationData" should "return 202 when successful" in {
    val departureId = DepartureId(DepartureId.fromHex("617b162bb367d2bc"))
    val request     = FakeRequest().withBody(Source.empty[ByteString])

    val result = controller(
      sendDeclarationResponse = () => Future.successful(Right(departureId))
    ).sendDeclarationData(request)

    status(result) shouldBe Status.ACCEPTED
    contentType(result) shouldBe Some(ContentTypes.JSON)
    contentAsJson(result) shouldBe JsString(departureId.hexString)
  }

  it should "return 400 when there is an XML schema validation error" in {
    val request = FakeRequest().withBody(Source.empty[ByteString])

    val error = XmlValidationError(
      MessageType.DeclarationData,
      NonEmptyList.of(
        SchemaValidationError(
          lineNumber = 0,
          columnNumber = 1,
          message = "Value 'ABC12345' is not facet-valid with respect to pattern"
        ),
        SchemaValidationError(
          lineNumber = 2,
          columnNumber = 3,
          message = "The value 'ABC12345' of element 'preparationDateAndTime' is not valid"
        )
      )
    )

    val result = controller(
      sendDeclarationResponse = () => Future.successful(Left(error))
    ).sendDeclarationData(request)

    status(result) shouldBe BAD_REQUEST
    contentType(result) shouldBe Some(ContentTypes.JSON)
    contentAsJson(result) shouldBe Json.obj(
      "code"    -> "SCHEMA_VALIDATION",
      "message" -> "Error while validating IE015 message",
      "errors" -> Json.arr(
        Json.obj(
          "lineNumber"   -> 0,
          "columnNumber" -> 1,
          "message"      -> "Value 'ABC12345' is not facet-valid with respect to pattern"
        ),
        Json.obj(
          "lineNumber"   -> 2,
          "columnNumber" -> 3,
          "message"      -> "The value 'ABC12345' of element 'preparationDateAndTime' is not valid"
        )
      )
    )
  }

  it should "return 500 when there is an upstream service error" in {
    val request = FakeRequest().withBody(Source.empty[ByteString])

    val error = UpstreamErrorResponse("Aarghh!!!", 400)

    val result = controller(
      sendDeclarationResponse = () => Future.successful(Left(UpstreamServiceError(cause = error)))
    ).sendDeclarationData(request)

    status(result) shouldBe INTERNAL_SERVER_ERROR
    contentType(result) shouldBe Some(ContentTypes.JSON)
    contentAsJson(result) shouldBe Json.obj(
      "code"    -> "INTERNAL_SERVER_ERROR",
      "message" -> "Internal server error"
    )
  }

  it should "return 500 when there is an internal service error" in {
    val request = FakeRequest().withBody(Source.empty[ByteString])

    val result = controller(
      sendDeclarationResponse = () => Future.successful(Left(InternalServiceError()))
    ).sendDeclarationData(request)

    status(result) shouldBe INTERNAL_SERVER_ERROR
    contentType(result) shouldBe Some(ContentTypes.JSON)
    contentAsJson(result) shouldBe Json.obj(
      "code"    -> "INTERNAL_SERVER_ERROR",
      "message" -> "Internal server error"
    )
  }

  it should "return 500 when there is an unexpected kind of error for this endpoint" in {
    val request = FakeRequest().withBody(Source.empty[ByteString])

    val result = controller(
      sendDeclarationResponse =
        () => Future.successful(Left(NotFoundError("Didn't find something")))
    ).sendDeclarationData(request)

    status(result) shouldBe INTERNAL_SERVER_ERROR
    contentType(result) shouldBe Some(ContentTypes.JSON)
    contentAsJson(result) shouldBe Json.obj(
      "code"    -> "INTERNAL_SERVER_ERROR",
      "message" -> "Internal server error"
    )
  }

  it should "return 500 when there is an unhandled runtime exception" in {
    val request = FakeRequest().withBody(Source.empty[ByteString])

    val result = controller(
      sendDeclarationResponse = () => Future.failed(new RuntimeException)
    ).sendDeclarationData(request)

    status(result) shouldBe INTERNAL_SERVER_ERROR
    contentType(result) shouldBe Some(ContentTypes.JSON)
    contentAsJson(result) shouldBe Json.obj(
      "code"    -> "INTERNAL_SERVER_ERROR",
      "message" -> "Internal server error"
    )
  }

  "DeparturesController.receiveMessage" should "return 202 when successful" in {
    val departureId = DepartureId(DepartureId.fromHex("617b162bb367d2bc"))
    val messageId   = MessageId(MessageId.fromHex("617b162bb367d2bc"))

    val request = FakeRequest()
      .withHeaders(Constants.MessageTypeHeader -> MessageType.DeclarationAmendment.code)
      .withBody(Source.empty[ByteString])

    val result = controller(
      receiveMessageResponse = () => Future.successful(Right(messageId))
    ).receiveMessage(departureId)(request)

    status(result) shouldBe Status.ACCEPTED
    contentType(result) shouldBe Some(ContentTypes.JSON)
    contentAsJson(result) shouldBe JsString(messageId.hexString)
  }

  it should "return 404 when the departure could not be found" in {
    val departureId = DepartureId(DepartureId.fromHex("617b162bb367d2bc"))
    val request = FakeRequest()
      .withHeaders(Constants.MessageTypeHeader -> MessageType.DeclarationAmendment.code)
      .withBody(Source.empty[ByteString])

    val result = controller(
      receiveMessageResponse =
        () => Future.successful(Left(TransitMovementError.notFoundError(departureId)))
    ).receiveMessage(departureId)(request)

    status(result) shouldBe NOT_FOUND
    contentType(result) shouldBe Some(ContentTypes.JSON)
    contentAsJson(result) shouldBe Json.obj(
      "code"    -> "NOT_FOUND",
      "message" -> "The departure with ID 617b162bb367d2bc was not found"
    )
  }

  it should "return 400 when the X-Message-Type header is missing" in {
    val departureId = DepartureId(DepartureId.fromHex("617b162bb367d2bc"))
    val messageId   = MessageId(MessageId.fromHex("617b162bb367d2bc"))
    val request     = FakeRequest().withBody(Source.empty[ByteString])

    val result = controller(
      receiveMessageResponse = () => Future.successful(Right(messageId))
    ).receiveMessage(departureId)(request)

    status(result) shouldBe BAD_REQUEST
    contentType(result) shouldBe Some(ContentTypes.JSON)
    contentAsJson(result) shouldBe Json.obj(
      "code"    -> "BAD_REQUEST",
      "message" -> "Missing or incorrect X-Message-Type header"
    )
  }

  it should "return 400 when the X-Message-Type header is not a valid request message" in {
    val departureId = DepartureId(DepartureId.fromHex("617b162bb367d2bc"))
    val messageId   = MessageId(MessageId.fromHex("617b162bb367d2bc"))
    val request = FakeRequest()
      .withHeaders(Constants.MessageTypeHeader -> MessageType.UnloadingPermission.code)
      .withBody(Source.empty[ByteString])

    val result = controller(
      receiveMessageResponse = () => Future.successful(Right(messageId))
    ).receiveMessage(departureId)(request)

    status(result) shouldBe BAD_REQUEST
    contentType(result) shouldBe Some(ContentTypes.JSON)
    contentAsJson(result) shouldBe Json.obj(
      "code"    -> "BAD_REQUEST",
      "message" -> "Missing or incorrect X-Message-Type header"
    )
  }

  it should "return 400 when the X-Message-Type header is not a valid departure message" in {
    val departureId = DepartureId(DepartureId.fromHex("617b162bb367d2bc"))
    val messageId   = MessageId(MessageId.fromHex("617b162bb367d2bc"))
    val request = FakeRequest()
      .withHeaders(Constants.MessageTypeHeader -> MessageType.ArrivalNotification.code)
      .withBody(Source.empty[ByteString])

    val result = controller(
      receiveMessageResponse = () => Future.successful(Right(messageId))
    ).receiveMessage(departureId)(request)

    status(result) shouldBe BAD_REQUEST
    contentType(result) shouldBe Some(ContentTypes.JSON)
    contentAsJson(result) shouldBe Json.obj(
      "code"    -> "BAD_REQUEST",
      "message" -> "Missing or incorrect X-Message-Type header"
    )
  }

  it should "return 400 when there is an XML schema validation error" in {
    val departureId = DepartureId(DepartureId.fromHex("617b162bb367d2bc"))
    val request = FakeRequest()
      .withHeaders(Constants.MessageTypeHeader -> MessageType.DeclarationAmendment.code)
      .withBody(Source.empty[ByteString])

    val error = XmlValidationError(
      MessageType.DeclarationData,
      NonEmptyList.of(
        SchemaValidationError(
          lineNumber = 0,
          columnNumber = 1,
          message = "Value 'ABC12345' is not facet-valid with respect to pattern"
        ),
        SchemaValidationError(
          lineNumber = 2,
          columnNumber = 3,
          message = "The value 'ABC12345' of element 'preparationDateAndTime' is not valid"
        )
      )
    )

    val result = controller(
      receiveMessageResponse = () => Future.successful(Left(error))
    ).receiveMessage(departureId)(request)

    status(result) shouldBe BAD_REQUEST
    contentType(result) shouldBe Some(ContentTypes.JSON)
    contentAsJson(result) shouldBe Json.obj(
      "code"    -> "SCHEMA_VALIDATION",
      "message" -> "Error while validating IE015 message",
      "errors" -> Json.arr(
        Json.obj(
          "lineNumber"   -> 0,
          "columnNumber" -> 1,
          "message"      -> "Value 'ABC12345' is not facet-valid with respect to pattern"
        ),
        Json.obj(
          "lineNumber"   -> 2,
          "columnNumber" -> 3,
          "message"      -> "The value 'ABC12345' of element 'preparationDateAndTime' is not valid"
        )
      )
    )
  }

  it should "return 500 when there is an upstream service error" in {
    val departureId = DepartureId(DepartureId.fromHex("617b162bb367d2bc"))

    val request = FakeRequest()
      .withHeaders(Constants.MessageTypeHeader -> MessageType.DeclarationAmendment.code)
      .withBody(Source.empty[ByteString])

    val error = UpstreamErrorResponse("Aarghh!!!", 400)

    val result = controller(
      receiveMessageResponse = () => Future.successful(Left(UpstreamServiceError(cause = error)))
    ).receiveMessage(departureId)(request)

    status(result) shouldBe INTERNAL_SERVER_ERROR
    contentType(result) shouldBe Some(ContentTypes.JSON)
    contentAsJson(result) shouldBe Json.obj(
      "code"    -> "INTERNAL_SERVER_ERROR",
      "message" -> "Internal server error"
    )
  }

  it should "return 500 when there is an internal service error" in {
    val departureId = DepartureId(DepartureId.fromHex("617b162bb367d2bc"))

    val request = FakeRequest()
      .withHeaders(Constants.MessageTypeHeader -> MessageType.DeclarationAmendment.code)
      .withBody(Source.empty[ByteString])

    val result = controller(
      receiveMessageResponse = () => Future.successful(Left(InternalServiceError()))
    ).receiveMessage(departureId)(request)

    status(result) shouldBe INTERNAL_SERVER_ERROR
    contentType(result) shouldBe Some(ContentTypes.JSON)
    contentAsJson(result) shouldBe Json.obj(
      "code"    -> "INTERNAL_SERVER_ERROR",
      "message" -> "Internal server error"
    )
  }

  it should "return 500 when there is an unexpected kind of error for this endpoint" in {
    val departureId = DepartureId(DepartureId.fromHex("617b162bb367d2bc"))

    val request = FakeRequest()
      .withHeaders(Constants.MessageTypeHeader -> MessageType.DeclarationAmendment.code)
      .withBody(Source.empty[ByteString])

    val result = controller(
      receiveMessageResponse =
        () => Future.successful(Left(BadRequestError("Didn't like the input!")))
    ).receiveMessage(departureId)(request)

    status(result) shouldBe INTERNAL_SERVER_ERROR
    contentType(result) shouldBe Some(ContentTypes.JSON)
    contentAsJson(result) shouldBe Json.obj(
      "code"    -> "INTERNAL_SERVER_ERROR",
      "message" -> "Internal server error"
    )
  }

  it should "return 500 when there is an unhandled runtime exception" in {
    val departureId = DepartureId(DepartureId.fromHex("617b162bb367d2bc"))

    val request = FakeRequest()
      .withHeaders(Constants.MessageTypeHeader -> MessageType.DeclarationAmendment.code)
      .withBody(Source.empty[ByteString])

    val result = controller(
      receiveMessageResponse = () => Future.failed(new RuntimeException)
    ).receiveMessage(departureId)(request)

    status(result) shouldBe INTERNAL_SERVER_ERROR
    contentType(result) shouldBe Some(ContentTypes.JSON)
    contentAsJson(result) shouldBe Json.obj(
      "code"    -> "INTERNAL_SERVER_ERROR",
      "message" -> "Internal server error"
    )
  }
}
