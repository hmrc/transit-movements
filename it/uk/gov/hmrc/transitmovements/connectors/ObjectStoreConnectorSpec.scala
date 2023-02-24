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

import akka.NotUsed
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.objectstore.client.Md5Hash
import uk.gov.hmrc.objectstore.client.Object
import uk.gov.hmrc.objectstore.client.ObjectMetadata
import uk.gov.hmrc.objectstore.client.Path.File
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.transitmovements.connectors.BaseUnitSpec
import uk.gov.hmrc.transitmovements.connectors.ObjectStoreConnectorImpl

import java.time.Instant
import java.util.UUID.randomUUID
import scala.concurrent.Future
import scala.util.Try

class ObjectStoreConnectorSpec extends AnyWordSpec with Matchers with MockitoSugar with ScalaFutures with BaseUnitSpec {

  "ObjectStoreConnector" should {
    "return the contents of a file" in new ObjectStoreConnectorFixture {
      val objectStoreConnector = new ObjectStoreConnectorImpl(objectStoreClient)

      val source = objectStoreConnector.getObjectStoreFile(filename).futureValue
      val result = source.runWith(Sink.head).futureValue

      result.utf8String mustBe content
    }
    "fail" when {
      "a file has not being found" in new ObjectStoreConnectorFixture {
        val objectStoreConnector = new ObjectStoreConnectorImpl(objectStoreClient)
        when(objectStoreClient.getObject(any[File](), any())(any(), any())).thenReturn(Future.successful(None))

        val source = Try(objectStoreConnector.getObjectStoreFile(filename).futureValue)

        source mustBe Symbol("failure")
      }
    }
  }

  class ObjectStoreConnectorFixture {
    val filename                                                                    = "movements/movementId/abc.xml"
    val objectStoreClient                                                           = mock[PlayObjectStoreClient]
    val metadata                                                                    = ObjectMetadata("", 0, Md5Hash(""), Instant.now(), Map.empty[String, String])
    val content                                                                     = "content"
    def content(value: String = randomUUID().toString): Source[ByteString, NotUsed] = Source.single(ByteString(value))
    val o                                                                           = Option[Object[Source[ByteString, NotUsed]]](Object.apply(File(filename), Source.single(ByteString(content)), metadata))
    when(objectStoreClient.getObject[Source[ByteString, NotUsed]](any[File](), any())(any(), any())).thenReturn(Future.successful(o))
  }

}
