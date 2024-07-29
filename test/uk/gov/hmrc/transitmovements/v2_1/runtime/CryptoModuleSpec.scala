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

package uk.gov.hmrc.transitmovements.v2_1.runtime

import org.mockito.MockitoSugar.mock
import org.mockito.MockitoSugar.when
import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.crypto.AesGCMCrypto
import uk.gov.hmrc.crypto.Crypted
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.crypto.PlainBytes
import uk.gov.hmrc.crypto.PlainText
import uk.gov.hmrc.transitmovements.config.AppConfig
import uk.gov.hmrc.transitmovements.runtime.CryptoModule

import java.nio.charset.StandardCharsets

class CryptoModuleSpec extends AnyFreeSpec with Matchers with ScalaCheckDrivenPropertyChecks {

  "provideCrypto" - {
    "returns aesGcmCrypto by default" in {
      val config = mock[AppConfig]
      when(config.encryptionEnabled).thenReturn(true)
      when(config.encryptionKey).thenReturn("4c1BM/RgBldkSef9Ov9/DXStbJDMWDW9kuZSr6Za6PA=")

      new CryptoModule().provideCrypto(config) mustBe a[AesGCMCrypto]
    }

    "returns a NoEncryption if encryption is off" in {
      // Because we hide the object to ensure it's not accidentally accessed, we look for the special "toString" entry
      // which will be "Do Not Use Does Not Encrypt"
      val config = mock[AppConfig]
      when(config.encryptionEnabled).thenReturn(false)

      val result = new CryptoModule().provideCrypto(config)
      result must not be a[AesGCMCrypto]
      result.toString mustBe "Do Not Use Does Not Encrypt"
    }
  }

  "NoEncryption" - {
    val config = mock[AppConfig]
    when(config.encryptionEnabled).thenReturn(false)

    val noCrypto: Encrypter with Decrypter = new CryptoModule().provideCrypto(config)

    "encrypt does not encrypt a string" in forAll(Gen.alphaNumStr) {
      string =>
        noCrypto.encrypt(PlainText(string)).value mustBe string
    }

    "encrypt does not encrypt bytes" in forAll(Gen.alphaNumStr.map(_.getBytes(StandardCharsets.UTF_8))) {
      bytes =>
        noCrypto.encrypt(PlainBytes(bytes)).value.getBytes(StandardCharsets.UTF_8) mustBe bytes
    }

    "decrypt string is actually the identity" in forAll(Gen.alphaNumStr) {
      string =>
        noCrypto.decrypt(Crypted(string)).value mustBe string
    }

    "decrypt bytes is actually the identity" in forAll(Gen.alphaNumStr.map(_.getBytes(StandardCharsets.UTF_8))) {
      bytes =>
        noCrypto.decryptAsBytes(Crypted(new String(bytes, StandardCharsets.UTF_8))).value mustBe bytes
    }
  }

}
