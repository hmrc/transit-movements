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

package uk.gov.hmrc.transitmovements.services

import cats.implicits.catsSyntaxEitherId
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import uk.gov.hmrc.http.HttpVerbs.POST
import uk.gov.hmrc.transitmovements.base.StreamTestHelpers
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.controllers.routes
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MovementType

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SourceManagementServiceSpec extends AnyWordSpec with Matchers with ScalaFutures with TestActorSystem {

  ".replicateSource" should {
    "replicate the source" in new Setup {
      whenReady(service.replicateSource(source, 4).value) {
        result =>
          result.map {
            replicatedSources =>
              replicatedSources.foreach {
                replicatedSource =>
                  whenReady(convertSource(replicatedSource)) {
                    convertedResult =>
                      convertedResult shouldBe expectedBody
                  }
              }
          }
      }
    }
  }

  ".replicateRequestSource" should {
    "replicate the source that is wrapped in a request" in new Setup {
      val request: FakeRequest[Source[ByteString, _]] = FakeRequest(
        method = POST,
        uri = routes.MovementsController.createMovement(EORINumber("GB123456789012"), MovementType.Arrival).url,
        headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.XML, "X-Message-Type" -> MessageType.ArrivalNotification.code)),
        body = source
      )

      whenReady(service.replicateRequestSource(request, 4).value) {
        result =>
          result.map {
            replicatedSources =>
              replicatedSources.foreach {
                replicatedSource =>
                  whenReady(convertSource(replicatedSource)) {
                    convertedResult =>
                      convertedResult shouldBe expectedBody
                  }
              }
          }
      }
    }
  }

  ".calculateSize" should {
    "return the size of the source" in new Setup {
      whenReady(service.calculateSize(source).value) {
        result =>
          result shouldBe expectedBody.length.asRight
      }
    }
  }

  trait Setup extends StreamTestHelpers {

    def convertSource(source: Source[ByteString, _]): Future[String] = source.runFold("")(
      (acc, byteString) => acc + byteString.utf8String
    )

    val expectedBody                  = "successful"
    val source: Source[ByteString, _] = createStream(expectedBody)
    val service                       = new SourceManagementServiceImpl
  }
}
