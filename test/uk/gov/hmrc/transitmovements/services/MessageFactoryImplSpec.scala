/*
 * Copyright 2022 HM Revenue & Customs
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

import java.io.File
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.services.errors.StreamError

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

    val sut = new MessageFactoryImpl(clock, random)

    "will create a message with a body when given a stream" in {
      val stream = FileIO.fromPath(tempFile.path)

      val result = sut.create(MessageType.DestinationOfficeRejection, instant, Some(MessageId("123")), stream)

      whenReady(result.value) {
        r =>
          r.isRight mustBe true
      }
    }

    "will return a Left when a NonFatal exception is thrown as a StreamError" in {
      val stream = FileIO.fromFile(new File(""), 5)

      val result = sut.create(MessageType.RequestOfRelease, instant, Some(MessageId("456")), stream)

      whenReady(result.value) {
        r =>
          r.isLeft mustBe true
          r.left.get.isInstanceOf[StreamError] mustBe true
      }

    }
  }
}
