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

import akka.stream.scaladsl.FileIO
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import play.api.libs.Files.SingletonTemporaryFileCreator
import uk.gov.hmrc.transitmovements.base.SpecBase
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageStatus.Pending
import uk.gov.hmrc.transitmovements.models.MessageStatus.Received
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.ObjectStoreURI
import uk.gov.hmrc.transitmovements.services.errors.StreamError

import java.io.File
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

class MessageFactoryImplSpec extends SpecBase with ScalaFutures with Matchers with TestActorSystem with ModelGenerators {
  "create" - {
    val instant: OffsetDateTime = OffsetDateTime.of(2022, 8, 26, 9, 0, 0, 0, ZoneOffset.UTC)
    val clock: Clock            = Clock.fixed(instant.toInstant, ZoneOffset.UTC)
    val random                  = new SecureRandom

    val tempFile = SingletonTemporaryFileCreator.create()

    val sut = new MessageFactoryImpl(clock, random)(materializer, materializer.executionContext)

    "will create a message with a body when given a stream" in {
      val stream    = FileIO.fromPath(tempFile.path)
      val triggerId = Some(MessageId("123"))

      val result = sut.create(MessageType.DestinationOfficeRejection, instant, instant, triggerId, stream, Received)

      whenReady(result.value) {
        r =>
          r.isRight mustBe true
          val message = r.toOption.get
          message mustBe Message(message.id, message.received, Some(instant), MessageType.DestinationOfficeRejection, triggerId, None, Some(""), Some(Received))
      }
    }

    "will return a Left when a NonFatal exception is thrown as a StreamError" in {
      val stream    = FileIO.fromPath(new File("").toPath, 5)
      val triggerId = Some(MessageId("456"))

      val result = sut.create(MessageType.RequestOfRelease, instant, instant, triggerId, stream, Received)

      whenReady(result.value) {
        case Left(_: StreamError) => succeed
        case x                    => fail(s"Expected a Left(StreamError), got $x")
      }

    }
  }

  "createEmptyMessage" - {
    val instant: OffsetDateTime = OffsetDateTime.of(2022, 8, 26, 9, 0, 0, 0, ZoneOffset.UTC)
    val clock: Clock            = Clock.fixed(instant.toInstant, ZoneOffset.UTC)
    val random                  = new SecureRandom

    val sut = new MessageFactoryImpl(clock, random)(materializer, materializer.executionContext)

    "will create a message without a body" in {
      val result = sut.createEmptyMessage(MessageType.ArrivalNotification, instant)

      result mustBe Message(result.id, result.received, None, MessageType.ArrivalNotification, None, None, None, Some(Pending))

    }

  }

  "createSmallMessage" - {
    val instant: OffsetDateTime = OffsetDateTime.of(2022, 8, 26, 9, 0, 0, 0, ZoneOffset.UTC)
    val clock: Clock            = Clock.fixed(instant.toInstant, ZoneOffset.UTC)
    val random                  = new SecureRandom

    val sut = new MessageFactoryImpl(clock, random)(materializer, materializer.executionContext)

    "will create a message when given an object store uri" in {
      val objectStoreURI = ObjectStoreURI(value = "test")
      val triggerId      = Some(MessageId("123"))

      val result = sut.createSmallMessage(MessageId("123"), MessageType.DestinationOfficeRejection, instant, instant, triggerId, objectStoreURI, Received)

      result mustBe Message(
        result.id,
        result.received,
        Some(instant),
        MessageType.DestinationOfficeRejection,
        result.triggerId,
        result.uri,
        None,
        Some(Received)
      )
    }
  }
}
