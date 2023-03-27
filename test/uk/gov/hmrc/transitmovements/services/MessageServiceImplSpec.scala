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
import cats.data.EitherT
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.{eq => eqTo}
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.Md5Hash
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.Path.{File => OSFile}
import uk.gov.hmrc.transitmovements.base.SpecBase
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageStatus
import uk.gov.hmrc.transitmovements.models.MessageStatus.Pending
import uk.gov.hmrc.transitmovements.models.MessageStatus.Received
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.ObjectStoreResourceLocation
import uk.gov.hmrc.transitmovements.models.ObjectStoreURI
import uk.gov.hmrc.transitmovements.models.mongo.UpdateMessageModel
import uk.gov.hmrc.transitmovements.services.errors.MessageError
import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError

import java.net.URI
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.control.NonFatal

class MessageServiceImplSpec extends SpecBase with ScalaFutures with Matchers with TestActorSystem with ModelGenerators with ScalaCheckDrivenPropertyChecks {
  "create" - {
    val instant: OffsetDateTime = OffsetDateTime.of(2022, 8, 26, 9, 0, 0, 0, ZoneOffset.UTC)
    val clock: Clock            = Clock.fixed(instant.toInstant, ZoneOffset.UTC)
    val random                  = new SecureRandom

    "will create a message with a body when given a small stream" in forAll(
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[MessageId].map(Some.apply),
      Gen.stringOfN(15, Gen.alphaNumChar)
    ) {
      (movementId, messageId, triggerId, string) =>
        val stream = Source.single[ByteString](ByteString(string))

        val mockObjectStoreService: ObjectStoreService             = mock[ObjectStoreService]
        val mockSmallMessageLimitService: SmallMessageLimitService = mock[SmallMessageLimitService]
        when(mockSmallMessageLimitService.isLarge(any[Long])).thenReturn(false)
        val sut = new MessageServiceImpl(clock, random, mockObjectStoreService, mockSmallMessageLimitService)(materializer, materializer.executionContext) {
          override def generateId(): MessageId = messageId
        }

        val result = sut.create(movementId, MessageType.DestinationOfficeRejection, instant, instant, triggerId, stream, 15, Received)(HeaderCarrier())

        whenReady(result.value) {
          case Right(
                Message(
                  `messageId`,
                  _,
                  Some(`instant`),
                  Some(MessageType.DestinationOfficeRejection),
                  `triggerId`,
                  None,
                  Some(`string`),
                  Some(Received)
                )
              ) =>
            succeed
          case x => fail(s"Failed: got $x")
        }
    }

    "will create a message with a uri when given a large stream" in forAll(
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[MessageId].map(Some.apply),
      Gen.stringOfN(15, Gen.alphaNumChar)
    ) {
      (movementId, messageId, triggerId, string) =>
        val stream         = Source.single[ByteString](ByteString(string))
        val objectStoreURI = testObjectStoreURI(movementId, messageId, instant)

        val mockObjectStoreService: ObjectStoreService = mock[ObjectStoreService]
        when(
          mockObjectStoreService
            .getObjectStoreFile(ObjectStoreResourceLocation(eqTo(objectStoreURI.asResourceLocation.get.value)))(any[ExecutionContext], any[HeaderCarrier])
        ).thenReturn(
          EitherT.rightT(Source.single(ByteString("1")))
        )
        val mockSmallMessageLimitService: SmallMessageLimitService = mock[SmallMessageLimitService]
        when(mockSmallMessageLimitService.isLarge(any[Long])).thenReturn(true)
        val sut = new MessageServiceImpl(clock, random, mockObjectStoreService, mockSmallMessageLimitService)(materializer, materializer.executionContext) {
          override def generateId(): MessageId = messageId
        }

        val result = sut.create(movementId, MessageType.DestinationOfficeRejection, instant, instant, triggerId, stream, 15, Received)(HeaderCarrier())

        whenReady(result.value) {
          r =>
            r.isRight mustBe true
            val message = r.toOption.get
            message mustBe Message(
              messageId,
              message.received,
              Some(instant),
              Some(MessageType.DestinationOfficeRejection),
              triggerId,
              Some(new URI(objectStoreURI.value)),
              None,
              Some(Received)
            )
        }
    }

    "will return a Left when a NonFatal exception from Object Store is thrown as a MessageError" in forAll(
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[MessageId].map(Some.apply)
    ) {
      (movementId, messageId, triggerId) =>
        val stream = Source.failed(new IllegalArgumentException())

        val mockObjectStoreService: ObjectStoreService = mock[ObjectStoreService]
        when(
          mockObjectStoreService
            .putObjectStoreFile(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(stream))(any[ExecutionContext], any[HeaderCarrier])
        )
          .thenReturn(EitherT.leftT(ObjectStoreError.UnexpectedError(None)))
        val mockSmallMessageLimitService: SmallMessageLimitService = mock[SmallMessageLimitService]
        when(mockSmallMessageLimitService.isLarge(any[Long])).thenReturn(true)
        val sut = new MessageServiceImpl(clock, random, mockObjectStoreService, mockSmallMessageLimitService)(materializer, materializer.executionContext) {
          override def generateId(): MessageId = messageId
        }

        val result = sut.create(movementId, MessageType.RequestOfRelease, instant, instant, triggerId, stream, 4, Received)(HeaderCarrier())

        whenReady(result.value) {
          case Left(_: MessageError) => succeed
          case x                     => fail(s"Expected a Left(MessageError), got $x")
        }

    }

  }

  "createEmptyMessage" - {
    val instant: OffsetDateTime = OffsetDateTime.of(2022, 8, 26, 9, 0, 0, 0, ZoneOffset.UTC)
    val clock: Clock            = Clock.fixed(instant.toInstant, ZoneOffset.UTC)
    val random                  = new SecureRandom

    val mockObjectStoreService: ObjectStoreService             = mock[ObjectStoreService]
    val mockSmallMessageLimitService: SmallMessageLimitService = mock[SmallMessageLimitService]
    val sut                                                    = new MessageServiceImpl(clock, random, mockObjectStoreService, mockSmallMessageLimitService)(materializer, materializer.executionContext)

    "will create a message without a body" in {
      val result = sut.createEmpty(Some(MessageType.ArrivalNotification), instant)

      result mustBe Message(result.id, result.received, None, Some(MessageType.ArrivalNotification), None, None, None, Some(Pending))

    }

  }

  "createWithURI" - {

    val instant: OffsetDateTime = OffsetDateTime.of(2022, 8, 26, 9, 0, 0, 0, ZoneOffset.UTC)
    val clock: Clock            = Clock.fixed(instant.toInstant, ZoneOffset.UTC)
    val random                  = new SecureRandom

    val mockObjectStoreService: ObjectStoreService             = mock[ObjectStoreService]
    val mockSmallMessageLimitService: SmallMessageLimitService = mock[SmallMessageLimitService]

    "creates a message with the appropriate URI" in forAll(
      arbitrary[MessageType],
      arbitrary[MessageId],
      arbitrary[MessageId].map(Some.apply),
      arbitrary[ObjectStoreURI],
      arbitrary[MessageStatus]
    ) {
      (messageType, messageId, triggerId, objectStoreURI, messageStatus) =>
        val sut = new MessageServiceImpl(clock, random, mockObjectStoreService, mockSmallMessageLimitService)(materializer, materializer.executionContext) {
          override def generateId(): MessageId = messageId
        }

        val result = sut.createWithURI(messageType, instant, instant, triggerId, objectStoreURI, messageStatus)
        result mustBe Message(messageId, instant, Some(instant), Some(messageType), triggerId, Some(new URI(objectStoreURI.value)), None, Some(messageStatus))
    }

  }

  "update" - {

    val instant: OffsetDateTime = OffsetDateTime.of(2022, 8, 26, 9, 0, 0, 0, ZoneOffset.UTC)
    val clock: Clock            = Clock.fixed(instant.toInstant, ZoneOffset.UTC)
    val random                  = new SecureRandom

    "for small messages" - {

      val mockSmallMessageLimitService: SmallMessageLimitService = mock[SmallMessageLimitService]
      when(mockSmallMessageLimitService.isLarge(any[Long])).thenReturn(false)

      "when successful get an appropriate model" in forAll(
        arbitrary[MovementId],
        arbitrary[MessageId],
        Gen.stringOfN(5, Gen.alphaNumChar),
        Gen.chooseNum(1L, Int.MaxValue),
        arbitrary[MessageStatus]
      ) {
        (movementID, messageID, payload, size, messageStatus) =>
          val mockObjectStoreService: ObjectStoreService = mock[ObjectStoreService]
          val sut                                        = new MessageServiceImpl(clock, random, mockObjectStoreService, mockSmallMessageLimitService)
          val result                                     = sut.update(movementID, messageID, Source.single(ByteString(payload)), size, messageStatus)(HeaderCarrier())
          whenReady(result.value) {
            r =>
              r mustBe Right(UpdateMessageModel(None, Some(payload), messageStatus))
              verify(mockObjectStoreService, times(0)).putObjectStoreFile(MovementId(anyString()), MessageId(anyString()), any[Source[ByteString, _]])(
                any[ExecutionContext],
                any[HeaderCarrier]
              )
          }
      }

      "when failed return a failure" in forAll(arbitrary[MovementId], arbitrary[MessageId], Gen.chooseNum(1L, Long.MaxValue), arbitrary[MessageStatus]) {
        (movementID, messageID, size, messageStatus) =>
          val error                                      = new IllegalArgumentException()
          val mockObjectStoreService: ObjectStoreService = mock[ObjectStoreService]
          val sut                                        = new MessageServiceImpl(clock, random, mockObjectStoreService, mockSmallMessageLimitService)
          val result                                     = sut.update(movementID, messageID, Source.failed(error), size, messageStatus)(HeaderCarrier())
          whenReady(result.value) {
            r =>
              r mustBe Left(MessageError.UnexpectedError(Some(error)))
              verify(mockObjectStoreService, times(0)).putObjectStoreFile(MovementId(anyString()), MessageId(anyString()), any[Source[ByteString, _]])(
                any[ExecutionContext],
                any[HeaderCarrier]
              )
          }
      }
    }

    "for large messages" - {
      val instant: OffsetDateTime = OffsetDateTime.of(2022, 8, 26, 9, 0, 0, 0, ZoneOffset.UTC)
      val clock: Clock            = Clock.fixed(instant.toInstant, ZoneOffset.UTC)
      val random                  = new SecureRandom

      val mockSmallMessageLimitService: SmallMessageLimitService = mock[SmallMessageLimitService]
      when(mockSmallMessageLimitService.isLarge(any[Long])).thenReturn(true)

      "when successful get an appropriate model" in forAll(
        arbitrary[MovementId],
        arbitrary[MessageId],
        Gen.stringOfN(5, Gen.alphaNumChar),
        Gen.chooseNum(1L, Long.MaxValue),
        arbitrary[MessageStatus],
        arbitrary[ObjectSummaryWithMd5]
      ) {
        (movementID, messageID, payload, size, messageStatus, summary) =>
          val mockObjectStoreService: ObjectStoreService = mock[ObjectStoreService]

          when(
            mockObjectStoreService.putObjectStoreFile(MovementId(eqTo(movementID.value)), MessageId(eqTo(messageID.value)), any[Source[ByteString, _]])(
              any[ExecutionContext],
              any[HeaderCarrier]
            )
          )
            .thenReturn(EitherT.rightT(summary))

          val sut    = new MessageServiceImpl(clock, random, mockObjectStoreService, mockSmallMessageLimitService)
          val result = sut.update(movementID, messageID, Source.single(ByteString(payload)), size, messageStatus)(HeaderCarrier())
          whenReady(result.value) {
            r =>
              r mustBe Right(UpdateMessageModel(None, Some(payload), messageStatus))
              verify(mockObjectStoreService, times(1)).putObjectStoreFile(any[MovementId], any[MessageId], any[Source[ByteString, _]])(
                any[ExecutionContext],
                any[HeaderCarrier]
              )
          }
      }

      "when failed return a failure" in forAll(arbitrary[MovementId], arbitrary[MessageId], Gen.chooseNum(1L, Long.MaxValue), arbitrary[MessageStatus]) {
        (movementID, messageID, size, messageStatus) =>
          val error                                      = new IllegalArgumentException()
          val mockObjectStoreService: ObjectStoreService = mock[ObjectStoreService]
          when(
            mockObjectStoreService.putObjectStoreFile(MovementId(eqTo(movementID.value)), MessageId(eqTo(messageID.value)), any[Source[ByteString, _]])(
              any[ExecutionContext],
              any[HeaderCarrier]
            )
          )
            .thenReturn(EitherT.leftT(ObjectStoreError.UnexpectedError(None)))

          val sut    = new MessageServiceImpl(clock, random, mockObjectStoreService, mockSmallMessageLimitService)
          val result = sut.update(movementID, messageID, Source.failed(error), size, messageStatus)(HeaderCarrier())
          whenReady(result.value) {
            r =>
              r mustBe Left(MessageError.UnexpectedError(Some(error)))
              verify(mockObjectStoreService, times(1)).putObjectStoreFile(any[MovementId], any[MessageId], any[Source[ByteString, _]])(
                any[ExecutionContext],
                any[HeaderCarrier]
              )
          }
      }

    }
  }
}
