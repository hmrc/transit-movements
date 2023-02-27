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

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.reset
import org.mockito.MockitoSugar.when
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures.whenReady
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.Status.INTERNAL_SERVER_ERROR
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.objectstore.client.Md5Hash
import uk.gov.hmrc.objectstore.client.Object
import uk.gov.hmrc.objectstore.client.ObjectMetadata
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.Path.File
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.transitmovements.base.StreamTestHelpers
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.ObjectStoreURI
import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError

import java.time.Instant
import java.util.UUID.randomUUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ObjectStoreServiceSpec
    extends AnyFreeSpec
    with Matchers
    with MockitoSugar
    with TestActorSystem
    with StreamTestHelpers
    with BeforeAndAfterEach
    with ModelGenerators
    with ScalaCheckDrivenPropertyChecks {

  implicit val hc: HeaderCarrier                           = HeaderCarrier()
  private val mockObjectStoreClient: PlayObjectStoreClient = mock[PlayObjectStoreClient]

  override def beforeEach: Unit =
    reset(mockObjectStoreClient)

  "Object Store service" - {

    "should return the contents of a file" in {
      val filePath =
        Path.Directory(s"common-transit-convention-traders/movements/${arbitraryMovementId.arbitrary.sample.get}").file(randomUUID.toString).asUri
      val metadata    = ObjectMetadata("", 0, Md5Hash(""), Instant.now(), Map.empty[String, String])
      val content     = "content"
      val fileContent = Option[Object[Source[ByteString, NotUsed]]](Object.apply(File(filePath), Source.single(ByteString(content)), metadata))
      when(mockObjectStoreClient.getObject[Source[ByteString, NotUsed]](any[File](), any())(any(), any())).thenReturn(Future.successful(fileContent))
      val service = new ObjectStoreServiceImpl(mockObjectStoreClient)
      val result  = service.getObjectStoreFile(ObjectStoreURI(filePath))
      whenReady(result.value) {
        r =>
          r.isRight mustBe true
          r.toOption.get mustBe fileContent.get.content

      }
    }

    "should return an error when the file is not found on path" in {
      when(mockObjectStoreClient.getObject(any[File](), any())(any(), any())).thenReturn(Future.successful(None))
      val service = new ObjectStoreServiceImpl(mockObjectStoreClient)
      val result  = service.getObjectStoreFile(ObjectStoreURI("abc/movement/abc.xml"))
      whenReady(result.value) {
        r =>
          r.isLeft mustBe true
          r.left.getOrElse(ObjectStoreError.FileNotFound) mustBe a[ObjectStoreError.FileNotFound]
      }
    }

    "on a failed submission, should return a Left with an UnexpectedError" in {
      val error = UpstreamErrorResponse("error", INTERNAL_SERVER_ERROR)
      when(mockObjectStoreClient.getObject(any[File](), any())(any(), any())).thenReturn(Future.failed(error))
      val service = new ObjectStoreServiceImpl(mockObjectStoreClient)
      val result  = service.getObjectStoreFile(ObjectStoreURI("abc/movement/abc.xml"))
      whenReady(result.value) {
        _ mustBe Left(ObjectStoreError.UnexpectedError(Some(error)))
      }
    }

  }

}