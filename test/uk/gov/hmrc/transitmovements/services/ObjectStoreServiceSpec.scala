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

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.{eq => eqTo}
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoSugar.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures.whenReady
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.MimeTypes
import play.api.http.Status.INTERNAL_SERVER_ERROR
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.objectstore.client.Md5Hash
import uk.gov.hmrc.objectstore.client.Object
import uk.gov.hmrc.objectstore.client.ObjectMetadata
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.{Path => OSPath}
import uk.gov.hmrc.objectstore.client.RetentionPeriod
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.transitmovements.base.StreamTestHelpers
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.ObjectStoreResourceLocation
import uk.gov.hmrc.transitmovements.models.requests.common.MessageId
import uk.gov.hmrc.transitmovements.models.requests.common.MovementId
import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError

import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
  private val dateTimeFormatter                            = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss.SSS").withZone(ZoneOffset.UTC)
  private val now                                          = OffsetDateTime.now(ZoneOffset.UTC)
  private val clock                                        = Clock.fixed(now.toInstant, ZoneOffset.UTC)

  override def beforeEach(): Unit =
    reset(mockObjectStoreClient)

  "getObjectStoreFile" - {

    "should return the contents of a file" in {
      val filePath =
        OSPath.Directory(s"movements/${arbitraryMovementId.arbitrary.sample.get}").file(randomUUID.toString).asUri
      val metadata    = ObjectMetadata("", 0, Md5Hash(""), Instant.now(), Map.empty[String, String])
      val content     = "content"
      val fileContent = Option[Object[Source[ByteString, NotUsed]]](Object.apply(OSPath.File(filePath), Source.single(ByteString(content)), metadata))

      when(mockObjectStoreClient.getObject[Source[ByteString, NotUsed]](eqTo(OSPath.File(filePath)), eqTo("transit-movements"))(any(), any()))
        .thenReturn(Future.successful(fileContent))
      val service = new ObjectStoreServiceImpl()(materializer, clock, mockObjectStoreClient)
      val result  = service.getObjectStoreFile(ObjectStoreResourceLocation(filePath))
      whenReady(result.value) {
        r =>
          r.isRight mustBe true
          r.toOption.get mustBe fileContent.get.content

      }
    }

    "should return an error when the file is not found on path" in {
      when(mockObjectStoreClient.getObject(any[OSPath.File](), eqTo("transit-movements"))(any(), any())).thenReturn(Future.successful(None))
      val service = new ObjectStoreServiceImpl()(materializer, clock, mockObjectStoreClient)

      val result = service.getObjectStoreFile(ObjectStoreResourceLocation("abc/movement/abc.xml"))
      whenReady(result.value) {
        case Left(_: ObjectStoreError.FileNotFound) => succeed
        case x =>
          fail(s"Expected Left(ObjectStoreError.FileNotFound), instead got $x")

      }
    }

    "on a failed submission, should return a Left with an UnexpectedError" in {
      val error = UpstreamErrorResponse("error", INTERNAL_SERVER_ERROR)

      when(mockObjectStoreClient.getObject(any[OSPath.File](), eqTo("transit-movements"))(any(), any())).thenReturn(Future.failed(error))
      val service = new ObjectStoreServiceImpl()(materializer, clock, mockObjectStoreClient)
      val result  = service.getObjectStoreFile(ObjectStoreResourceLocation("abc/movement/abc.xml"))
      whenReady(result.value) {
        case Left(_: ObjectStoreError.UnexpectedError) => succeed
        case x =>
          fail(s"Expected Left(ObjectStoreError.UnexpectedError), instead got $x")
      }
    }
  }

  "putObjectStoreFile" - {

    "should add the content to a file" in forAll(arbitrary[MovementId], arbitrary[MessageId]) {
      (movementId, messageId) =>
        val objectSummary: ObjectSummaryWithMd5 = arbitraryObjectSummaryWithMd5.arbitrary.sample.get

        val source: Source[ByteString, _] = Source.single(ByteString("<test>test</test>"))

        when(
          mockObjectStoreClient.putObject(
            path = eqTo(
              OSPath.Directory(s"movements/${movementId.value}").file(s"${movementId.value}-${messageId.value}-${dateTimeFormatter.format(now)}.xml")
            ),
            content = eqTo(source),
            retentionPeriod = any[RetentionPeriod],
            contentType = eqTo(Some(MimeTypes.XML)),
            contentMd5 = any[Option[Md5Hash]],
            owner = eqTo("transit-movements")
          )(any(), any())
        )
          .thenReturn(Future.successful(objectSummary))
        val service = new ObjectStoreServiceImpl()(materializer, clock, mockObjectStoreClient)

        val result = service.putObjectStoreFile(movementId, messageId, source)
        whenReady(result.value) {
          r =>
            r mustBe Right(objectSummary)

            verify(mockObjectStoreClient, times(1)).putObject(
              path = eqTo(
                OSPath.Directory(s"movements/${movementId.value}").file(s"${movementId.value}-${messageId.value}-${dateTimeFormatter.format(now)}.xml")
              ),
              content = eqTo(source),
              retentionPeriod = any[RetentionPeriod],
              contentType = eqTo(Some(MimeTypes.XML)),
              contentMd5 = any[Option[Md5Hash]],
              owner = eqTo("transit-movements")
            )(any(), any())
        }
    }

    "on a failed submission of content in Object store, should return a left" in forAll(arbitrary[MovementId], arbitrary[MessageId]) {
      (movementId, messageId) =>
        val source: Source[ByteString, _] = Source.single(ByteString("<test>test</test>"))

        val error = ObjectStoreError.UnexpectedError(Some(new Throwable("test")))
        when(
          mockObjectStoreClient.putObject(
            path = eqTo(
              OSPath.Directory(s"movements/${movementId.value}").file(s"${movementId.value}-${messageId.value}-${dateTimeFormatter.format(now)}.xml")
            ),
            content = eqTo(source),
            retentionPeriod = any[RetentionPeriod],
            contentType = eqTo(Some(MimeTypes.XML)),
            contentMd5 = any[Option[Md5Hash]],
            owner = eqTo("transit-movements")
          )(any(), any())
        )
          .thenReturn(Future.failed(error))
        val service = new ObjectStoreServiceImpl()(materializer, clock, mockObjectStoreClient)
        val result  = service.putObjectStoreFile(movementId, messageId, source)

        whenReady(result.value) {
          case Left(_: ObjectStoreError.UnexpectedError) =>
            verify(mockObjectStoreClient, times(1)).putObject(
              path = eqTo(
                OSPath.Directory(s"movements/${movementId.value}").file(s"${movementId.value}-${messageId.value}-${dateTimeFormatter.format(now)}.xml")
              ),
              content = eqTo(source),
              retentionPeriod = any[RetentionPeriod],
              contentType = eqTo(Some(MimeTypes.XML)),
              contentMd5 = any[Option[Md5Hash]],
              owner = eqTo("transit-movements")
            )(any(), any())
          case x =>
            fail(s"Expected Left(ObjectStoreError.UnexpectedError), instead got $x")
        }
    }
  }

}
