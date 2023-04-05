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
import cats.data.EitherT
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchersSugar.eqTo
import org.mockito.MockitoSugar.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.Status.CREATED
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
import play.api.libs.json.JsSuccess
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers
import play.api.test.Helpers.contentAsJson
import play.api.test.Helpers.contentAsString
import play.api.test.Helpers.defaultAwaitTimeout
import play.api.test.Helpers.status
import uk.gov.hmrc.objectstore.client.Md5Hash
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.transitmovements.base.SpecBase
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.ObjectStoreSummaryResponse
import uk.gov.hmrc.transitmovements.models.ObjectStoreURI
import uk.gov.hmrc.transitmovements.services.ObjectStoreService
import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError

import java.time.Instant
import scala.annotation.nowarn
import scala.concurrent.ExecutionContext.Implicits.global

// mocks are causing this warning, we don't need it.
@nowarn("msg=dead code following this construct")
class ObjectStoreControllerSpec extends SpecBase with ModelGenerators with ScalaCheckDrivenPropertyChecks with TestActorSystem {

  "get" - {
    "a valid URI returns a stream" in forAll(arbitrary[ObjectStoreURI]) {
      objectStoreURI =>
        val sampleSource           = Source.single[ByteString](ByteString("test object"))
        val mockObjectStoreService = mock[ObjectStoreService]
        when(mockObjectStoreService.getObjectStoreFile(eqTo(objectStoreURI.asResourceLocation.get))(any(), any())).thenReturn(EitherT.rightT(sampleSource))

        val sut    = new ObjectStoreController(Helpers.stubControllerComponents(), mockObjectStoreService)
        val result = sut.getObject(objectStoreURI)(FakeRequest("GET", s"object/${objectStoreURI.value}"))

        status(result) mustBe OK
        contentAsString(result) mustBe "test object"
    }

    "an invalid URI returns NotFound" in forAll(arbitrary[ObjectStoreURI]) {
      objectStoreURI =>
        val mockObjectStoreService = mock[ObjectStoreService]
        when(mockObjectStoreService.getObjectStoreFile(eqTo(objectStoreURI.asResourceLocation.get))(any(), any()))
          .thenReturn(EitherT.leftT(ObjectStoreError.FileNotFound(objectStoreURI.value)))

        val sut    = new ObjectStoreController(Helpers.stubControllerComponents(), mockObjectStoreService)
        val result = sut.getObject(objectStoreURI)(FakeRequest("GET", s"object/${objectStoreURI.value}"))

        status(result) mustBe NOT_FOUND
    }
  }

  "put (with message and movement ID)" - {
    "a successful put returns the object information" in forAll(arbitrary[MovementId], arbitrary[MessageId]) {
      (movementId, messageId) =>
        val sampleStream = Source.single[ByteString](ByteString("test"))
        val now          = Instant.now()
        val objectSummary = ObjectSummaryWithMd5(
          Path.File(Path.Directory(s"transit-movements/movements/${movementId.value}"), s"${movementId.value}-${messageId.value}-date.xml"),
          4,
          Md5Hash("098f6bcd4621d373cade4e832627b4f6"),
          now
        )
        val mockObjectStoreService = mock[ObjectStoreService]
        when(mockObjectStoreService.putObjectStoreFile(eqTo(movementId), eqTo(messageId), eqTo(sampleStream))(any(), any()))
          .thenReturn(EitherT.rightT(objectSummary))

        val sut = new ObjectStoreController(Helpers.stubControllerComponents(), mockObjectStoreService)
        val result = sut.putObject(movementId, messageId)(
          FakeRequest("POST", s"object/movement/${movementId.value}/message/${messageId.value}", FakeHeaders(), sampleStream)
        )

        status(result) mustBe CREATED
        contentAsJson(result).validate[ObjectStoreSummaryResponse] mustBe JsSuccess(
          ObjectStoreSummaryResponse(
            s"transit-movements/movements/${movementId.value}/${movementId.value}-${messageId.value}-date.xml",
            4,
            "098f6bcd4621d373cade4e832627b4f6",
            now
          )
        )
    }

    "a failed put returns an error" in forAll(arbitrary[MovementId], arbitrary[MessageId]) {
      (movementId, messageId) =>
        val sampleStream           = Source.single[ByteString](ByteString("test"))
        val mockObjectStoreService = mock[ObjectStoreService]
        when(mockObjectStoreService.putObjectStoreFile(eqTo(movementId), eqTo(messageId), eqTo(sampleStream))(any(), any()))
          .thenReturn(EitherT.leftT(ObjectStoreError.UnexpectedError(None)))

        val sut = new ObjectStoreController(Helpers.stubControllerComponents(), mockObjectStoreService)
        val result = sut.putObject(movementId, messageId)(
          FakeRequest("POST", s"object/movement/${movementId.value}/message/${messageId.value}", FakeHeaders(), sampleStream)
        )

        status(result) mustBe INTERNAL_SERVER_ERROR
    }
  }

}
