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

import akka.stream.IOResult
import akka.stream.scaladsl.FileIO
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import play.api.libs.Files.SingletonTemporaryFileCreator
import uk.gov.hmrc.transitmovements.base.SpecBase
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.DeclarationData
import uk.gov.hmrc.transitmovements.models.EORINumber
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

class DepartureFactoryImplSpec extends SpecBase with ScalaFutures with Matchers with TestActorSystem with ModelGenerators {

  val instant: OffsetDateTime = OffsetDateTime.of(2022, 5, 27, 11, 0, 0, 0, ZoneOffset.UTC)
  val clock: Clock            = Clock.fixed(instant.toInstant, ZoneOffset.UTC)
  val random                  = new SecureRandom

  "create" - {
    val sut = new DepartureFactoryImpl(clock, random)

    "will create a departure with a message" in {
      val departure = sut.create(EORINumber("1"), DeclarationData(EORINumber("1"), instant), arbitraryMessage.arbitrary.sample.get)

      departure.messages.length mustBe 1
    }
  }
}
