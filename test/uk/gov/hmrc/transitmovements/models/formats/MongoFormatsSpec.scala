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

import org.mockito.MockitoSugar.mock
import org.mockito.MockitoSugar.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.libs.json.JsError
import play.api.libs.json.JsString
import play.api.libs.json.JsSuccess
import play.api.libs.json.Json
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.transitmovements.base.TestEncrypters.AddEncEncrypter
import uk.gov.hmrc.transitmovements.base.TestEncrypters.NoEncryption
import uk.gov.hmrc.transitmovements.config.AppConfig
import uk.gov.hmrc.transitmovements.generators.ModelGenerators

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class MongoFormatsSpec extends AnyFreeSpec with Matchers with ModelGenerators with OptionValues with ScalaCheckDrivenPropertyChecks {

  "OffsetDateTime" - {
    implicit val crypto: Encrypter with Decrypter = NoEncryption
    val appConfig: AppConfig                      = mock[AppConfig]
    when(appConfig.encryptionTolerantRead).thenReturn(true)

    val sut: MongoFormats = new MongoFormats(appConfig)

    "OffsetDateTime, when written to Json, must be in a format Mongo can consume" in {

      val dateTime     = arbitrary[OffsetDateTime].sample.value
      val timeInMillis = dateTime.toInstant.toEpochMilli

      sut.offsetDateTimeWrites.writes(dateTime) mustBe Json.obj(
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

      sut.offsetDateTimeReads.reads(mongoDateTimeFormat).get mustBe Instant.ofEpochMilli(long).atOffset(ZoneOffset.UTC)
    }

  }

  "Sensitive String with fallback" - {
    implicit val crypto: Encrypter with Decrypter = AddEncEncrypter
    val appConfig: AppConfig                      = mock[AppConfig]
    when(appConfig.encryptionTolerantRead).thenReturn(true)

    val sut: MongoFormats = new MongoFormats(appConfig)

    "writing a SensitiveString returns the expected result" in forAll(
      Gen
        .stringOfN(10, Gen.alphaNumChar)
        .map(
          x => SensitiveString(x)
        )
    ) {
      str =>
        sut.sensitiveStringFormat.writes(str) mustBe JsString(s"enc-${str.decryptedValue}")
    }

    "reading an 'encrypted' SensitiveString should return the 'unencrypted' version" in forAll(Gen.stringOfN(10, Gen.alphaNumChar)) {
      str =>
        sut.sensitiveStringFormat.reads(JsString(s"enc-$str")) mustBe JsSuccess(SensitiveString(str))
    }

    "reading an 'unencrypted' SensitiveString should return the 'unencrypted' version" in forAll(Gen.stringOfN(10, Gen.alphaNumChar)) {
      str =>
        sut.sensitiveStringFormat.reads(JsString(str)) mustBe JsSuccess(SensitiveString(str))
    }
  }

  "Sensitive String without fallback" - {
    implicit val crypto: Encrypter with Decrypter = AddEncEncrypter
    val appConfig: AppConfig                      = mock[AppConfig]
    when(appConfig.encryptionTolerantRead).thenReturn(false)

    val sut: MongoFormats = new MongoFormats(appConfig)

    "writing a SensitiveString returns the expected result" in forAll(
      Gen
        .stringOfN(10, Gen.alphaNumChar)
        .map(
          x => SensitiveString(x)
        )
    ) {
      str =>
        sut.sensitiveStringFormat.writes(str) mustBe JsString(s"enc-${str.decryptedValue}")
    }

    "reading an 'encrypted' SensitiveString should return the 'unencrypted' version" in forAll(Gen.stringOfN(10, Gen.alphaNumChar)) {
      str =>
        sut.sensitiveStringFormat.reads(JsString(s"enc-$str")) mustBe JsSuccess(SensitiveString(str))
    }

    "reading an 'unencrypted' SensitiveString should fail" in forAll(Gen.stringOfN(10, Gen.alphaNumChar)) {
      str =>
        sut.sensitiveStringFormat.reads(JsString(str)) mustBe a[JsError]
    }
  }
}
