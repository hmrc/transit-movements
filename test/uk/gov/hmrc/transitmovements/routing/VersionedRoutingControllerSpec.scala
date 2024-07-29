/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.transitmovements.routing

import cats.implicits.catsSyntaxOptionId
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.OK
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers.contentAsString
import play.api.test.Helpers.defaultAwaitTimeout
import play.api.test.Helpers.status
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.config.Constants
import uk.gov.hmrc.transitmovements.controllers.actions.{InternalAuthActionProvider => TransitionalInternalAuthAction}
import uk.gov.hmrc.transitmovements.v2_1.controllers.actions.InternalAuthActionProvider
import uk.gov.hmrc.transitmovements.controllers.{MessageBodyController => TransitionalMessageBodyController}
import uk.gov.hmrc.transitmovements.models.MovementType
import uk.gov.hmrc.transitmovements.models.requests.common.EORINumber
import uk.gov.hmrc.transitmovements.models.requests.common.ItemCount
import uk.gov.hmrc.transitmovements.models.requests.common.LocalReferenceNumber
import uk.gov.hmrc.transitmovements.models.requests.common.MessageId
import uk.gov.hmrc.transitmovements.models.requests.common.MovementId
import uk.gov.hmrc.transitmovements.models.requests.common.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.requests.common.PageNumber
import uk.gov.hmrc.transitmovements.services.{MessageService => TransitionalMessageService}
import uk.gov.hmrc.transitmovements.services.{MessagesXmlParsingService => TransitionalMessagesXmlParsingService}
import uk.gov.hmrc.transitmovements.services.{MovementsXmlParsingService => TransitionalMovementsXmlParsingService}
import uk.gov.hmrc.transitmovements.services.{ObjectStoreService => TransitionalObjectStoreService}
import uk.gov.hmrc.transitmovements.services.{PersistenceService => TransitionalPersistenceService}
import uk.gov.hmrc.transitmovements.v2_1.controllers.MessageBodyController
import uk.gov.hmrc.transitmovements.v2_1.controllers.MovementsController
import uk.gov.hmrc.transitmovements.controllers.{MovementsController => TransitionalMovementsController}
import uk.gov.hmrc.transitmovements.v2_1.models
import uk.gov.hmrc.transitmovements.v2_1.services.MessageService
import uk.gov.hmrc.transitmovements.v2_1.services.MessagesXmlParsingService
import uk.gov.hmrc.transitmovements.v2_1.services.MovementFactory
import uk.gov.hmrc.transitmovements.services.{MovementFactory => TransitionalMovementFactory}
import uk.gov.hmrc.transitmovements.v2_1.services.MovementsXmlParsingService
import uk.gov.hmrc.transitmovements.v2_1.services.ObjectStoreService
import uk.gov.hmrc.transitmovements.v2_1.services.PersistenceService

import scala.concurrent.ExecutionContext.Implicits.global
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.OffsetDateTime
import scala.concurrent.Future

class VersionedRoutingControllerSpec extends AnyWordSpec with Matchers with MockitoSugar with ScalaFutures {

  "getBody" should {
    "call the transitional controller when APIVersion non 'final' value has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.getBody(eori, validTransitionalMovementType, movementId, messageId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "anything")), source)
      val result  = controller.getBody(eori, validTransitionalMovementType, movementId, messageId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the transitional controller when no APIVersion header has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.getBody(eori, validTransitionalMovementType, movementId, messageId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq.empty), source)
      val result  = controller.getBody(eori, validTransitionalMovementType, movementId, messageId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the versioned controller when APIVersion header 'final' has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.getBody(eori, validTransitionalMovementType, movementId, messageId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "final")), source)
      val result  = controller.getBody(eori, validTransitionalMovementType, movementId, messageId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "final"
    }

    "return BAD_REQUEST when movementType is not found in non-versioned models" in new Setup {
      val route   = routes.VersionedRoutingController.getBody(eori, invalidMovementType, movementId, messageId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq.empty), source)
      val result  = controller.getBody(eori, invalidMovementType, movementId, messageId)(request)

      status(result) shouldBe BAD_REQUEST
    }

    "return BAD_REQUEST when movementType is not found in versioned models" in new Setup {
      val route   = routes.VersionedRoutingController.getBody(eori, invalidMovementType, movementId, messageId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "final")), source)
      val result  = controller.getBody(eori, invalidMovementType, movementId, messageId)(request)

      status(result) shouldBe BAD_REQUEST
    }
  }

  "createBody" should {
    "call the transitional controller when APIVersion non 'final' value has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.createBody(eori, validTransitionalMovementType, movementId, messageId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "anything")), source)
      val result  = controller.createBody(eori, validTransitionalMovementType, movementId, messageId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the transitional controller when no APIVersion header has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.createBody(eori, validTransitionalMovementType, movementId, messageId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq.empty), source)
      val result  = controller.createBody(eori, validTransitionalMovementType, movementId, messageId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the versioned controller when APIVersion header 'final' has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.createBody(eori, validTransitionalMovementType, movementId, messageId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "final")), source)
      val result  = controller.createBody(eori, validTransitionalMovementType, movementId, messageId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "final"
    }

    "return BAD_REQUEST when movementType is not found in non-versioned models" in new Setup {
      val route   = routes.VersionedRoutingController.createBody(eori, invalidMovementType, movementId, messageId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq.empty), source)
      val result  = controller.createBody(eori, invalidMovementType, movementId, messageId)(request)

      status(result) shouldBe BAD_REQUEST
    }

    "return BAD_REQUEST when movementType is not found in versioned models" in new Setup {
      val route   = routes.VersionedRoutingController.createBody(eori, invalidMovementType, movementId, messageId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "final")), source)
      val result  = controller.createBody(eori, invalidMovementType, movementId, messageId)(request)

      status(result) shouldBe BAD_REQUEST
    }
  }

  "createMovement" should {
    "call the transitional controller when APIVersion non 'final' value has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.createMovement(eori, validTransitionalMovementType)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "anything")), source)
      val result  = controller.createMovement(eori, validTransitionalMovementType)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the transitional controller when no APIVersion header has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.createMovement(eori, validTransitionalMovementType)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq.empty), source)
      val result  = controller.createMovement(eori, validTransitionalMovementType)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the versioned controller when APIVersion header 'final' has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.createMovement(eori, validTransitionalMovementType)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "final")), source)
      val result  = controller.createMovement(eori, validTransitionalMovementType)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "final"
    }

    "return BAD_REQUEST when movementType is not found in non-versioned models" in new Setup {
      val route   = routes.VersionedRoutingController.createMovement(eori, invalidMovementType)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq.empty), source)
      val result  = controller.createMovement(eori, invalidMovementType)(request)

      status(result) shouldBe BAD_REQUEST
    }

    "return BAD_REQUEST when movementType is not found in versioned models" in new Setup {
      val route   = routes.VersionedRoutingController.createMovement(eori, invalidMovementType)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "final")), source)
      val result  = controller.createMovement(eori, invalidMovementType)(request)

      status(result) shouldBe BAD_REQUEST
    }
  }

  "getMovementWithoutMessages" should {
    "call the transitional controller when APIVersion non 'final' value has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.getMovementWithoutMessages(eori, validTransitionalMovementType, movementId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "anything")), source)
      val result  = controller.getMovementWithoutMessages(eori, validTransitionalMovementType, movementId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the transitional controller when no APIVersion header has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.getMovementWithoutMessages(eori, validTransitionalMovementType, movementId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq.empty), source)
      val result  = controller.getMovementWithoutMessages(eori, validTransitionalMovementType, movementId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the versioned controller when APIVersion header 'final' has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.getMovementWithoutMessages(eori, validTransitionalMovementType, movementId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "final")), source)
      val result  = controller.getMovementWithoutMessages(eori, validTransitionalMovementType, movementId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "final"
    }

    "return BAD_REQUEST when movementType is not found in non-versioned models" in new Setup {
      val route   = routes.VersionedRoutingController.getMovementWithoutMessages(eori, invalidMovementType, movementId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq.empty), source)
      val result  = controller.getMovementWithoutMessages(eori, invalidMovementType, movementId)(request)

      status(result) shouldBe BAD_REQUEST
    }

    "return BAD_REQUEST when movementType is not found in versioned models" in new Setup {
      val route   = routes.VersionedRoutingController.getMovementWithoutMessages(eori, invalidMovementType, movementId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "final")), source)
      val result  = controller.getMovementWithoutMessages(eori, invalidMovementType, movementId)(request)

      status(result) shouldBe BAD_REQUEST
    }
  }

  "getMessage" should {
    "call the transitional controller when APIVersion non 'final' value has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.getMessage(eori, validTransitionalMovementType, movementId, messageId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "anything")), source)
      val result  = controller.getMessage(eori, validTransitionalMovementType, movementId, messageId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the transitional controller when no APIVersion header has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.getMessage(eori, validTransitionalMovementType, movementId, messageId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq.empty), source)
      val result  = controller.getMessage(eori, validTransitionalMovementType, movementId, messageId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the versioned controller when APIVersion header 'final' has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.getMessage(eori, validTransitionalMovementType, movementId, messageId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "final")), source)
      val result  = controller.getMessage(eori, validTransitionalMovementType, movementId, messageId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "final"
    }

    "return BAD_REQUEST when movementType is not found in non-versioned models" in new Setup {
      val route   = routes.VersionedRoutingController.getMessage(eori, invalidMovementType, movementId, messageId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq.empty), source)
      val result  = controller.getMessage(eori, invalidMovementType, movementId, messageId)(request)

      status(result) shouldBe BAD_REQUEST
    }

    "return BAD_REQUEST when movementType is not found in versioned models" in new Setup {
      val route   = routes.VersionedRoutingController.getMessage(eori, invalidMovementType, movementId, messageId)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "final")), source)
      val result  = controller.getMessage(eori, invalidMovementType, movementId, messageId)(request)

      status(result) shouldBe BAD_REQUEST
    }
  }

  "updateMessage" should {
    "call the transitional controller when APIVersion non 'final' value has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.updateMessage(eori, validTransitionalMovementType, movementId, messageId)
      val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON, Constants.APIVersionHeaderKey -> "anything"))
      val body = Json.obj(
        "status" -> messageStatus.toString
      )

      val request = FakeRequest(route.method, route.url, headers, body)
      val result  = controller.updateMessage(eori, validTransitionalMovementType, movementId, messageId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the transitional controller when no APIVersion header has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.updateMessage(eori, validTransitionalMovementType, movementId, messageId)
      val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
      val body = Json.obj(
        "status" -> messageStatus.toString
      )

      val request = FakeRequest(route.method, route.url, headers, body)
      val result  = controller.updateMessage(eori, validTransitionalMovementType, movementId, messageId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the versioned controller when APIVersion header 'final' has been sent" in new Setup {
      val route = routes.VersionedRoutingController.updateMessage(eori, validTransitionalMovementType, movementId, messageId)

      val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON, Constants.APIVersionHeaderKey -> "final"))
      val body = Json.obj(
        "status" -> messageStatus.toString
      )

      val request = FakeRequest(route.method, route.url, headers, body)
      val result  = controller.updateMessage(eori, validTransitionalMovementType, movementId, messageId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "final"
    }

    "return BAD_REQUEST when movementType is not found in non-versioned models" in new Setup {
      val route   = routes.VersionedRoutingController.updateMessage(eori, invalidMovementType, movementId, messageId)
      val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
      val body = Json.obj(
        "status" -> messageStatus.toString
      )

      val request = FakeRequest(route.method, route.url, headers, body)
      val result  = controller.updateMessage(eori, invalidMovementType, movementId, messageId)(request)

      status(result) shouldBe BAD_REQUEST
    }

    "return BAD_REQUEST when movementType is not found in versioned models" in new Setup {
      val route   = routes.VersionedRoutingController.updateMessage(eori, invalidMovementType, movementId, messageId)
      val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON, Constants.APIVersionHeaderKey -> "final"))
      val body = Json.obj(
        "status" -> messageStatus.toString
      )

      val request = FakeRequest(route.method, route.url, headers, body)
      val result  = controller.updateMessage(eori, invalidMovementType, movementId, messageId)(request)

      status(result) shouldBe BAD_REQUEST
    }
  }

  "updateMovement" should {
    "call the transitional controller when APIVersion non 'final' value has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.updateMovement(movementId, messageId.some)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "anything")), source)
      val result  = controller.updateMovement(movementId, messageId.some)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the transitional controller when no APIVersion header has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.updateMovement(movementId, messageId.some)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq.empty), source)
      val result  = controller.updateMovement(movementId, messageId.some)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the versioned controller when APIVersion header 'final' has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.updateMovement(movementId, messageId.some)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "final")), source)
      val result  = controller.updateMovement(movementId, messageId.some)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "final"
    }
  }

  "updateMessageStatus" should {
    "call the transitional controller when APIVersion non 'final' value has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.updateMessageStatus(movementId, messageId)
      val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON, Constants.APIVersionHeaderKey -> "anything"))
      val body = Json.obj(
        "status" -> messageStatus.toString
      )

      val request = FakeRequest(route.method, route.url, headers, body)
      val result  = controller.updateMessageStatus(movementId, messageId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the transitional controller when no APIVersion header has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.updateMessageStatus(movementId, messageId)
      val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON))
      val body = Json.obj(
        "status" -> messageStatus.toString
      )

      val request = FakeRequest(route.method, route.url, headers, body)
      val result  = controller.updateMessageStatus(movementId, messageId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the versioned controller when APIVersion header 'final' has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.updateMessageStatus(movementId, messageId)
      val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON, Constants.APIVersionHeaderKey -> "final"))
      val body = Json.obj(
        "status" -> messageStatus.toString
      )

      val request = FakeRequest(route.method, route.url, headers, body)
      val result  = controller.updateMessageStatus(movementId, messageId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "final"
    }
  }

  "getMessages" should {
    "call the transitional controller when APIVersion non 'final' value has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.getMessages(eori, validMovementType, movementId, None, None, None, None)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "anything")), source)
      val result  = controller.getMessages(eori, validMovementType, movementId, None, None, None, None)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the transitional controller when no APIVersion header has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.getMessages(eori, validMovementType, movementId, None, None, None, None)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq.empty), source)
      val result  = controller.getMessages(eori, validMovementType, movementId, None, None, None, None)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the versioned controller when APIVersion header 'final' has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.getMessages(eori, validMovementType, movementId, None, None, None, None)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "final")), source)
      val result  = controller.getMessages(eori, validMovementType, movementId, None, None, None, None)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "final"
    }

    "return BAD_REQUEST when movementType is not found in non-versioned models" in new Setup {
      val route   = routes.VersionedRoutingController.getMessages(eori, invalidMovementType, movementId, None, None, None, None)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq.empty), source)
      val result  = controller.getMessages(eori, invalidMovementType, movementId, None, None, None, None)(request)

      status(result) shouldBe BAD_REQUEST
    }

    "return BAD_REQUEST when movementType is not found in versioned models" in new Setup {
      val route   = routes.VersionedRoutingController.getMessages(eori, invalidMovementType, movementId, None, None, None, None)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "final")), source)
      val result  = controller.getMessages(eori, invalidMovementType, movementId, None, None, None, None)(request)

      status(result) shouldBe BAD_REQUEST
    }
  }

  "getMovementsForEori" should {
    "call the transitional controller when APIVersion non 'final' value has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.getMovementsForEori(eori, validMovementType)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "anything")), source)
      val result  = controller.getMovementsForEori(eori, validMovementType)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the transitional controller when no APIVersion header has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.getMovementsForEori(eori, validMovementType)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq.empty), source)
      val result  = controller.getMovementsForEori(eori, validMovementType)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "transitional"
    }

    "call the versioned controller when APIVersion header 'final' has been sent" in new Setup {
      val route   = routes.VersionedRoutingController.getMovementsForEori(eori, validMovementType)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "final")), source)
      val result  = controller.getMovementsForEori(eori, validMovementType)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "final"
    }

    "return BAD_REQUEST when movementType is not found in non-versioned models" in new Setup {
      val route   = routes.VersionedRoutingController.getMovementsForEori(eori, invalidMovementType)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq.empty), source)
      val result  = controller.getMovementsForEori(eori, invalidMovementType)(request)

      status(result) shouldBe BAD_REQUEST
    }

    "return BAD_REQUEST when movementType is not found in versioned models" in new Setup {
      val route   = routes.VersionedRoutingController.getMovementsForEori(eori, invalidMovementType)
      val request = FakeRequest(route.method, route.url, FakeHeaders(Seq(Constants.APIVersionHeaderKey -> "final")), source)
      val result  = controller.getMovementsForEori(eori, invalidMovementType)(request)

      status(result) shouldBe BAD_REQUEST
    }
  }

  trait Setup {

    implicit val materializer: Materializer = Materializer(TestActorSystem.system)

    val eori: EORINumber       = EORINumber("012345678912")
    val messageId: MessageId   = MessageId("test_message_id")
    val movementId: MovementId = MovementId("test_movement_id")
    val messageStatus          = 200

    val validMovementType             = "departures"
    val validTransitionalMovementType = "departures"
    val invalidMovementType           = "invalid"

    val source: Source[ByteString, NotUsed] = Source.single(ByteString("someValidSource", StandardCharsets.UTF_8))

    val mockTransitionalPersistenceService: TransitionalPersistenceService                 = mock[TransitionalPersistenceService]
    val mockTransitionalObjectStoreService: TransitionalObjectStoreService                 = mock[TransitionalObjectStoreService]
    val mockTransitionalMessagesXmlParsingService: TransitionalMessagesXmlParsingService   = mock[TransitionalMessagesXmlParsingService]
    val mockTransitionalMovementsXmlParsingService: TransitionalMovementsXmlParsingService = mock[TransitionalMovementsXmlParsingService]
    val mockTransitionalMessageService: TransitionalMessageService                         = mock[TransitionalMessageService]
    val mockTransitionalInternalAuth: TransitionalInternalAuthAction                       = mock[TransitionalInternalAuthAction]
    val mockTransitionalMovementFactory: TransitionalMovementFactory                       = mock[TransitionalMovementFactory]

    val mockPersistenceService: PersistenceService                 = mock[PersistenceService]
    val mockObjectStoreService: ObjectStoreService                 = mock[ObjectStoreService]
    val mockMessagesXmlParsingService: MessagesXmlParsingService   = mock[MessagesXmlParsingService]
    val mockMovementsXmlParsingService: MovementsXmlParsingService = mock[MovementsXmlParsingService]
    val mockMessageService: MessageService                         = mock[MessageService]
    val mockInternalAuth: InternalAuthActionProvider               = mock[InternalAuthActionProvider]
    val mockMovementFactory: MovementFactory                       = mock[MovementFactory]

    implicit val mockClock: Clock                     = mock[Clock]
    implicit val tempFilCreator: TemporaryFileCreator = mock[TemporaryFileCreator]

    val mockTransitionalMessageBodyController: TransitionalMessageBodyController = new TransitionalMessageBodyController(
      stubControllerComponents(),
      mockTransitionalPersistenceService,
      mockTransitionalObjectStoreService,
      mockTransitionalMessagesXmlParsingService,
      mockTransitionalMovementsXmlParsingService,
      mockTransitionalMessageService,
      mockTransitionalInternalAuth,
      mockClock
    ) {

      override def getBody(eori: EORINumber, movementType: MovementType, movementId: MovementId, messageId: MessageId): Action[AnyContent] =
        Action.async {
          Future.successful(Ok("transitional"))
        }

      override def createBody(eori: EORINumber, movementType: MovementType, movementId: MovementId, messageId: MessageId): Action[Source[ByteString, _]] =
        Action.async(streamFromMemory) {
          implicit request =>
            Future.successful(Ok("transitional"))
        }
    }

    val mockMessageBodyController: MessageBodyController = new MessageBodyController(
      stubControllerComponents(),
      mockPersistenceService,
      mockObjectStoreService,
      mockMessagesXmlParsingService,
      mockMovementsXmlParsingService,
      mockMessageService,
      mockInternalAuth,
      mockClock
    ) {

      override def getBody(eori: EORINumber, movementType: models.MovementType, movementId: MovementId, messageId: MessageId): Action[AnyContent] =
        Action.async {
          Future.successful(Ok("final"))
        }

      override def createBody(
        eori: EORINumber,
        movementType: models.MovementType,
        movementId: MovementId,
        messageId: MessageId
      ): Action[Source[ByteString, _]] =
        Action.async(streamFromMemory) {
          implicit request =>
            Future.successful(Ok("final"))
        }
    }

    val mockMovementsController: MovementsController = new MovementsController(
      stubControllerComponents(),
      mockMessageService,
      mockMovementFactory,
      mockPersistenceService,
      mockMovementsXmlParsingService,
      mockMessagesXmlParsingService,
      mockObjectStoreService,
      mockInternalAuth
    ) {

      override def createMovement(eori: EORINumber, movementType: models.MovementType): Action[Source[ByteString, _]] =
        Action.async(streamFromMemory) {
          implicit request =>
            Future.successful(Ok("final"))
        }

      override def updateMovement(movementId: MovementId, triggerId: Option[MessageId]): Action[Source[ByteString, _]] =
        Action.async(streamFromMemory) {
          implicit request =>
            Future.successful(Ok("final"))
        }

      override def updateMessageStatus(movementId: MovementId, messageId: MessageId): Action[JsValue] =
        Action.async(parse.json) {
          implicit request =>
            Future.successful(Ok("final"))
        }

      override def getMovementsForEori(
        eoriNumber: EORINumber,
        movementType: models.MovementType,
        updatedSince: Option[OffsetDateTime],
        movementEORI: Option[EORINumber],
        movementReferenceNumber: Option[MovementReferenceNumber],
        page: Option[PageNumber],
        count: Option[ItemCount],
        receivedUntil: Option[OffsetDateTime],
        localReferenceNumber: Option[LocalReferenceNumber]
      ): Action[AnyContent] =
        Action.async {
          _ =>
            Future.successful(Ok("final"))
        }

      override def getMovementWithoutMessages(eoriNumber: EORINumber, movementType: models.MovementType, movementId: MovementId): Action[AnyContent] =
        Action.async {
          _ =>
            Future.successful(Ok("final"))
        }

      override def getMessage(eoriNumber: EORINumber, movementType: models.MovementType, movementId: MovementId, messageId: MessageId): Action[AnyContent] =
        Action.async {
          _ =>
            Future.successful(Ok("final"))
        }

      override def updateMessage(eori: EORINumber, movementType: models.MovementType, movementId: MovementId, messageId: MessageId): Action[JsValue] =
        Action.async(parse.json) {
          _ =>
            Future.successful(Ok("final"))
        }

      override def getMessages(
        eoriNumber: EORINumber,
        movementType: models.MovementType,
        movementId: MovementId,
        receivedSince: Option[OffsetDateTime],
        page: Option[PageNumber],
        count: Option[ItemCount],
        receivedUntil: Option[OffsetDateTime]
      ): Action[AnyContent] =
        Action.async {
          _ =>
            Future.successful(Ok("final"))
        }
    }

    val mockTransitionalMovementsController: TransitionalMovementsController = new TransitionalMovementsController(
      stubControllerComponents(),
      mockTransitionalMessageService,
      mockTransitionalMovementFactory,
      mockTransitionalPersistenceService,
      mockTransitionalMovementsXmlParsingService,
      mockTransitionalMessagesXmlParsingService,
      mockTransitionalObjectStoreService,
      mockTransitionalInternalAuth
    ) {

      override def createMovement(eori: EORINumber, movementType: MovementType): Action[Source[ByteString, _]] =
        Action.async(streamFromMemory) {
          implicit request =>
            Future.successful(Ok("transitional"))
        }

      override def updateMovement(movementId: MovementId, triggerId: Option[MessageId]): Action[Source[ByteString, _]] =
        Action.async(streamFromMemory) {
          implicit request =>
            Future.successful(Ok("transitional"))
        }

      override def updateMessageStatus(movementId: MovementId, messageId: MessageId): Action[JsValue] =
        Action.async(parse.json) {
          implicit request =>
            Future.successful(Ok("transitional"))
        }

      override def getMovementsForEori(
        eoriNumber: EORINumber,
        movementType: MovementType,
        updatedSince: Option[OffsetDateTime],
        movementEORI: Option[EORINumber],
        movementReferenceNumber: Option[MovementReferenceNumber],
        page: Option[PageNumber],
        count: Option[ItemCount],
        receivedUntil: Option[OffsetDateTime],
        localReferenceNumber: Option[LocalReferenceNumber]
      ): Action[AnyContent] =
        Action.async {
          _ =>
            Future.successful(Ok("transitional"))
        }

      override def getMovementWithoutMessages(eoriNumber: EORINumber, movementType: MovementType, movementId: MovementId): Action[AnyContent] =
        Action.async {
          _ =>
            Future.successful(Ok("transitional"))
        }

      override def getMessages(
        eoriNumber: EORINumber,
        movementType: MovementType,
        movementId: MovementId,
        receivedSince: Option[OffsetDateTime],
        page: Option[PageNumber],
        count: Option[ItemCount],
        receivedUntil: Option[OffsetDateTime]
      ): Action[AnyContent] =
        Action.async {
          _ =>
            Future.successful(Ok("transitional"))
        }

      override def getMessage(eoriNumber: EORINumber, movementType: MovementType, movementId: MovementId, messageId: MessageId): Action[AnyContent] =
        Action.async {
          _ =>
            Future.successful(Ok("transitional"))
        }

      override def updateMessage(eori: EORINumber, movementType: MovementType, movementId: MovementId, messageId: MessageId): Action[JsValue] =
        Action.async(parse.json) {
          _ =>
            Future.successful(Ok("transitional"))
        }
    }

    val controller = new VersionedRoutingController(
      stubControllerComponents(),
      mockTransitionalMessageBodyController,
      mockMessageBodyController,
      mockTransitionalMovementsController,
      mockMovementsController
    )

  }
}
