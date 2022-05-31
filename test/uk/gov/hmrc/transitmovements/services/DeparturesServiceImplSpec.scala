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

import cats.data.EitherT
import cats.implicits.catsStdInstancesForFuture
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.verify
import org.mockito.MockitoSugar.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar.mock
import uk.gov.hmrc.transitmovements.models.responses.DeclarationResponse
import uk.gov.hmrc.transitmovements.models.DeclarationData
import uk.gov.hmrc.transitmovements.models.Departure
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.MovementMessageId
import uk.gov.hmrc.transitmovements.repositories.DeparturesRepository

import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import scala.concurrent.ExecutionContext.Implicits.global

class DeparturesServiceImplSpec extends AnyFreeSpec with ScalaFutures with Matchers {
  val instant: OffsetDateTime          = OffsetDateTime.of(2022, 5, 27, 11, 0, 0, 0, ZoneOffset.UTC)
  val clock: Clock                     = Clock.fixed(instant.toInstant, ZoneOffset.UTC)
  val random                           = new SecureRandom
  val repository: DeparturesRepository = mock[DeparturesRepository]

  "When handed declaration data, the departures service" - {
    val service = new DeparturesServiceImpl(repository, clock, random)

    "must create a Departure and persist it" in {
      val eori            = EORINumber("GB1234")
      val declarationData = DeclarationData(EORINumber("1111"), instant)

      when(repository.insert(any[Departure])).thenReturn(
        EitherT.rightT(DeclarationResponse(DepartureId("888"), MovementMessageId("111")))
      )

      val result = service.create(eori, declarationData)

      whenReady(result.value) {
        either =>
          either mustBe Right(DeclarationResponse(DepartureId("888"), MovementMessageId("111")))
          verify(repository).insert(any[Departure]())
      }
    }
  }
}
