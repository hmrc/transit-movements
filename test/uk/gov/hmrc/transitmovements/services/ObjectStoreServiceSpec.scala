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

package uk.gov.hmrc.transitmovements.services

import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.reset
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterEach
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.transitmovements.connectors.ObjectStoreConnector
import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ObjectStoreServiceSpec
    extends AnyFreeSpec
    with Matchers
    with OptionValues
    with ScalaFutures
    with MockitoSugar
    with ScalaCheckDrivenPropertyChecks
    with BeforeAndAfterEach {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  val mockObjectStoreConnector: ObjectStoreConnector = mock[ObjectStoreConnector]
  val sut                                            = new ObjectStoreServiceImpl(mockObjectStoreConnector)

  override def beforeEach(): Unit =
    reset(mockObjectStoreConnector)

  "get file from object store" - {

    "when object store file is found, should return Right" in {

      when(mockObjectStoreConnector.getObjectStoreFile(any())(any(), any()))
        .thenReturn(Future.successful(Source.single(ByteString("this is test content"))))

      val result = sut.getObjectStoreFile("object-store-uri")
      whenReady(result.value) {
        _ mustBe result.value.futureValue
      }
    }

    "when file is not found, should return a Left with an FileNotFound" in {
      when(mockObjectStoreConnector.getObjectStoreFile(any())(any(), any()))
        .thenReturn(Future.failed(UpstreamErrorResponse("not found", NOT_FOUND)))

      val result = sut.getObjectStoreFile("common/abc/IE015.xml")
      whenReady(result.value) {
        _ mustBe Left(ObjectStoreError.FileNotFound("common/abc/IE015.xml"))
      }
    }

    "on a failed submission, should return a Left with an UnexpectedError" in {
      val error = UpstreamErrorResponse("error", INTERNAL_SERVER_ERROR)
      when(mockObjectStoreConnector.getObjectStoreFile(any())(any(), any()))
        .thenReturn(Future.failed(error))

      val result = sut.getObjectStoreFile("common/IE015")
      whenReady(result.value) {
        _ mustBe Left(ObjectStoreError.UnexpectedError(Some(error)))
      }
    }

  }

}
