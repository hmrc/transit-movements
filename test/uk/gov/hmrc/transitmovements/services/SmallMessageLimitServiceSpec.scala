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

import org.mockito.Mockito.when
import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.transitmovements.config.AppConfig

class SmallMessageLimitServiceSpec extends AnyFreeSpec with MockitoSugar with ScalaCheckDrivenPropertyChecks {

  private val config: AppConfig = mock[AppConfig]

  val limit = 500000

  "Small message limit " - {
    val service = new SmallMessageLimitService(config)
    when(config.smallMessageSizeLimit).thenReturn(limit)

    "should return false when below the limit" in forAll(Gen.choose(1, limit)) {
      size =>
        service.checkContentSize(size) mustBe false
    }

    "should return true when below the limit" in forAll(Gen.choose(limit + 1, 5000000)) {
      size =>
        service.checkContentSize(size) mustBe true
    }

  }
}
