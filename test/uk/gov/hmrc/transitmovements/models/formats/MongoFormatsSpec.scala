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

package uk.gov.hmrc.transitmovements.models.formats

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json.Json
import uk.gov.hmrc.transitmovements.generators.ModelGenerators

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class MongoFormatsSpec extends AnyFreeSpec with Matchers with ModelGenerators with OptionValues {

  "OffsetDateTime, when written to Json, must be in a format Mongo can consume" in {

    val dateTime     = arbitrary[OffsetDateTime].sample.value
    val timeInMillis = dateTime.toInstant.toEpochMilli

    MongoFormats.offsetDateTimeWrites.writes(dateTime) mustBe Json.obj(
      "$date" -> Json.obj(
        "$numberLong" -> timeInMillis.toString
      )
    )
  }

  "An OffsetDateTime can be constructed when returned from Mongo" in {
    val long = Gen.chooseNum(0, Long.MaxValue).sample.value
    val mongoDateTimeFormat = Json.obj(
      "$date" -> Json.obj(
        "$numberLong" -> long.toString
      )
    )

    MongoFormats.offsetDateTimeReads.reads(mongoDateTimeFormat).get mustBe Instant.ofEpochMilli(long).atOffset(ZoneOffset.UTC)
  }

}
