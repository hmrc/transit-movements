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

package uk.gov.hmrc.transitmovements.models.values

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ShortUUIDSpec extends AnyFlatSpec with Matchers {
  val instant: OffsetDateTime = OffsetDateTime.now
  val clock: Clock            = Clock.fixed(instant.toInstant, ZoneOffset.UTC)
  val random                  = new SecureRandom

  "a valid short UUID " should "be created" in {
    val shortUUID = ShortUUID.next(clock, random)
    shortUUID should fullyMatch regex ShortUUID.ShortUUIDRegex
  }
}
