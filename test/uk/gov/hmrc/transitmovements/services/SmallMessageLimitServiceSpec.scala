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

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.transitmovements.config.AppConfig

class SmallMessageLimitServiceSpec extends AnyFreeSpec with MockitoSugar {

  private val config: AppConfig = mock[AppConfig]

  "Small message limit " - {

    "should return false when below the limit" in {
      val service = new SmallMessageLimitService(config)
      service.checkContentSize(config.smallMessageSizeLimit - 1) mustBe false
    }

    "should return true when above the limit" in {
      val service = new SmallMessageLimitService(config)
      service.checkContentSize(config.smallMessageSizeLimit + 1) mustBe true
    }

    "should return false when equal the limit" in {
      val service = new SmallMessageLimitService(config)
      service.checkContentSize(config.smallMessageSizeLimit) mustBe false
    }
  }
}