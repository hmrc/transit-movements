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

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import cats.data.EitherT
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.{eq => eqTo}
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoSugar.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.Md5Hash
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.{Path => OSPath}
import uk.gov.hmrc.transitmovements.base.SpecBase
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MessageStatus.Pending
import uk.gov.hmrc.transitmovements.models.MessageStatus.Received
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError
import uk.gov.hmrc.transitmovements.services.errors.StreamError

import java.net.URI
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import scala.concurrent.ExecutionContext.Implicits.global

class MessageServiceImplSpec extends SpecBase with ScalaFutures with Matchers with TestActorSystem with ModelGenerators with ScalaCheckDrivenPropertyChecks {
  "create" - {

    val instant: OffsetDateTime      = OffsetDateTime.now(ZoneOffset.UTC)
    val clock: Clock                 = Clock.fixed(instant.toInstant, ZoneOffset.UTC)
    val random                       = new SecureRandom
    val objectStoreServiceMock       = mock[ObjectStoreService]
    val smallMessageLimitServiceMock = mock[SmallMessageLimitService]

    val messageId = arbitrary[MessageId].sample.value

    val sut = new MessageServiceImpl(clock, random, objectStoreServiceMock, smallMessageLimitServiceMock)(materializer, materializer.executionContext) {
      override def generateId(): MessageId = messageId
    }

    def beforeTest(): Unit = {
      reset(objectStoreServiceMock)
      reset(smallMessageLimitServiceMock)
    }

    "will create a message with a body when given a stream which is a small message" in forAll(
      arbitrary[MovementId],
      Gen.option(arbitrary[MessageId]),
      arbitrary[MessageType]
    ) {
      (movementId, triggerId, messageType) =>
        beforeTest()

        when(smallMessageLimitServiceMock.isLarge(eqTo(4L))).thenReturn(false)
        val stream = Source.single(ByteString("test"))
        val result = sut.create(movementId, messageType, instant, instant, triggerId, 4, stream, Received)(HeaderCarrier())

        whenReady(result.value) {
          r =>
            r mustBe Right(Message(messageId, instant, Some(instant), Some(messageType), triggerId, None, Some("test"), Some(4), Some(Received)))

            verify(objectStoreServiceMock, times(0)).putObjectStoreFile(MovementId(any()), MessageId(any()), any())(any(), any()) // must not be called at all
        }
    }

    "will create a message with a body when given a stream which is a large message" in forAll(
      arbitrary[MovementId],
      Gen.option(arbitrary[MessageId]),
      arbitrary[MessageType]
    ) {
      (movementId, triggerId, messageType) =>
        beforeTest()

        when(smallMessageLimitServiceMock.isLarge(eqTo(4L))).thenReturn(true)
        val os                   = testObjectStoreURI(movementId, messageId, instant)
        val objectSummaryWithMd5 = ObjectSummaryWithMd5(OSPath.File(os.value), 4, Md5Hash(""), instant.toInstant)
        val stream               = Source.single(ByteString("test"))

        when(objectStoreServiceMock.putObjectStoreFile(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(stream))(any(), any()))
          .thenReturn(EitherT.rightT(objectSummaryWithMd5))
        val result = sut.create(movementId, messageType, instant, instant, triggerId, 4, stream, Received)(HeaderCarrier())

        whenReady(result.value) {
          r =>
            r mustBe Right(
              Message(
                messageId,
                instant,
                Some(instant),
                Some(messageType),
                triggerId,
                Some(new URI(objectSummaryWithMd5.location.asUri)),
                None,
                Some(4),
                Some(Received)
              )
            )

            verify(objectStoreServiceMock, times(1)).putObjectStoreFile(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(stream))(
              any(),
              any()
            )
        }
    }

    "will return a Left when a NonFatal exception is thrown as a StreamError for a small message" in forAll(
      arbitrary[MovementId],
      Gen.option(arbitrary[MessageId]),
      arbitrary[MessageType]
    ) {
      (movementId, triggerId, messageType) =>
        beforeTest()

        when(smallMessageLimitServiceMock.isLarge(eqTo(4L))).thenReturn(false)

        val error  = new IllegalStateException()
        val stream = Source.failed[ByteString](error)

        val result = sut.create(movementId, messageType, instant, instant, triggerId, 4, stream, Received)(HeaderCarrier())

        whenReady(result.value) {
          case Left(_: StreamError) =>
            verify(objectStoreServiceMock, times(0)).putObjectStoreFile(MovementId(any()), MessageId(any()), any())(any(), any()) // must not be called at all
          case x => fail(s"Expected a Left(StreamError), got $x")
        }

    }

    "will return a Left when a NonFatal exception is thrown as a StreamError for a large message" in forAll(
      arbitrary[MovementId],
      Gen.option(arbitrary[MessageId]),
      arbitrary[MessageType]
    ) {
      (movementId, triggerId, messageType) =>
        beforeTest()

        when(smallMessageLimitServiceMock.isLarge(eqTo(4L))).thenReturn(true)

        val error  = new IllegalStateException()
        val stream = Source.empty[ByteString]
        when(objectStoreServiceMock.putObjectStoreFile(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(stream))(any(), any()))
          .thenReturn(EitherT.leftT(ObjectStoreError.UnexpectedError(Some(error))))

        val result = sut.create(movementId, messageType, instant, instant, triggerId, 4, stream, Received)(HeaderCarrier())

        whenReady(result.value) {
          case Left(StreamError.UnexpectedError(Some(`error`))) =>
            verify(objectStoreServiceMock, times(1)).putObjectStoreFile(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(stream))(
              any(),
              any()
            )
          case x => fail(s"Expected a Left(StreamError.UnexpectedError), got $x")
        }

    }
  }

  "createEmptyMessage" - {
    val instant: OffsetDateTime = OffsetDateTime.of(2022, 8, 26, 9, 0, 0, 0, ZoneOffset.UTC)
    val clock: Clock            = Clock.fixed(instant.toInstant, ZoneOffset.UTC)
    val random                  = new SecureRandom

    val objectStoreServiceMock       = mock[ObjectStoreService]
    val smallMessageLimitServiceMock = mock[SmallMessageLimitService]
    val messageId                    = arbitrary[MessageId].sample.get

    val sut = new MessageServiceImpl(clock, random, objectStoreServiceMock, smallMessageLimitServiceMock)(materializer, materializer.executionContext) {
      override def generateId(): MessageId = messageId
    }

    "will create a message without a body" in {
      val result = sut.createEmptyMessage(Some(MessageType.ArrivalNotification), instant)
      result mustBe Message(messageId, instant, None, Some(MessageType.ArrivalNotification), None, None, None, None, Some(Pending))

    }

  }

}
