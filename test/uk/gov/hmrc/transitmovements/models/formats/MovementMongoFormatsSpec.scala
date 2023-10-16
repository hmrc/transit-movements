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

package uk.gov.hmrc.transitmovements.models.formats

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json.Json
import uk.gov.hmrc.crypto.Crypted
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.crypto.PlainBytes
import uk.gov.hmrc.crypto.PlainContent
import uk.gov.hmrc.crypto.PlainText
import uk.gov.hmrc.transitmovements.generators.ModelGenerators

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class MovementMongoFormatsSpec extends AnyFreeSpec with Matchers with ModelGenerators with OptionValues {

  object DummyCrypto extends Encrypter with Decrypter {

    override def encrypt(plain: PlainContent): Crypted = plain match {
      case PlainText(value)  => Crypted(value)
      case PlainBytes(value) => Crypted(new String(value, StandardCharsets.UTF_8))
    }

    override def decrypt(reversiblyEncrypted: Crypted): PlainText = PlainText(reversiblyEncrypted.value)

    override def decryptAsBytes(reversiblyEncrypted: Crypted): PlainBytes = PlainBytes(reversiblyEncrypted.value.getBytes(StandardCharsets.UTF_8))
  }

  lazy val movementMongoFormats: MovementMongoFormats = new MovementMongoFormats(false)(DummyCrypto)

  "OffsetDateTime, when written to Json, must be in a format Mongo can consume" in {

    val dateTime     = arbitrary[OffsetDateTime].sample.value
    val timeInMillis = dateTime.toInstant.toEpochMilli

    movementMongoFormats.offsetDateTimeWrites.writes(dateTime) mustBe Json.obj(
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

    movementMongoFormats.offsetDateTimeReads.reads(mongoDateTimeFormat).get mustBe Instant.ofEpochMilli(long).atOffset(ZoneOffset.UTC)
  }

}
