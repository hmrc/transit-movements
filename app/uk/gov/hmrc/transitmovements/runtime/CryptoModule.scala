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

package uk.gov.hmrc.transitmovements.runtime

import com.google.inject.AbstractModule
import com.google.inject.Provides
import play.api.Logging
import uk.gov.hmrc.crypto.Crypted
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.crypto.PlainBytes
import uk.gov.hmrc.crypto.PlainContent
import uk.gov.hmrc.crypto.PlainText
import uk.gov.hmrc.crypto.SymmetricCryptoFactory
import uk.gov.hmrc.transitmovements.config.AppConfig

import java.nio.charset.StandardCharsets
import javax.inject.Singleton

//noinspection ScalaUnusedSymbol
class CryptoModule extends AbstractModule with Logging {

  private object NoEncryption extends Encrypter with Decrypter {

    override def encrypt(plain: PlainContent): Crypted = plain match {
      case PlainText(x)  => Crypted(x)
      case PlainBytes(x) => Crypted(new String(x, StandardCharsets.UTF_8))
    }

    override def decrypt(reversiblyEncrypted: Crypted): PlainText = PlainText(reversiblyEncrypted.value)

    override def decryptAsBytes(reversiblyEncrypted: Crypted): PlainBytes = PlainBytes(reversiblyEncrypted.value.getBytes(StandardCharsets.UTF_8))

    override def toString: String = "Do Not Use Does Not Encrypt"
  }

  @Provides
  @Singleton
  def provideCrypto(config: AppConfig): Encrypter & Decrypter =
    if (config.encryptionEnabled) SymmetricCryptoFactory.aesGcmCrypto(config.encryptionKey)
    else {
      logger.error(
        """!!!YOU HAVE DISABLED MONGO FIELD-LEVEL ENCRYPTION!!!
          |
          |If you are seeing this log message on the External Test or Production environment, IMMEDIATELY change "encryption.enabled" to "true" in the environment/base config and redeploy.""".stripMargin
      )
      NoEncryption
    }

}
