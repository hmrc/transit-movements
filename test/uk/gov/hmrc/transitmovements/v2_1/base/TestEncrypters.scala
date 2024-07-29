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

package uk.gov.hmrc.transitmovements.v2_1.base

import uk.gov.hmrc.crypto.Crypted
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.crypto.PlainBytes
import uk.gov.hmrc.crypto.PlainContent
import uk.gov.hmrc.crypto.PlainText

import java.nio.charset.StandardCharsets

object TestEncrypters {

  object NoEncryption extends Encrypter with Decrypter {

    override def encrypt(plain: PlainContent): Crypted = plain match {
      case PlainText(x)  => Crypted(x)
      case PlainBytes(x) => Crypted(new String(x, StandardCharsets.UTF_8))
    }

    override def decrypt(reversiblyEncrypted: Crypted): PlainText = PlainText(reversiblyEncrypted.value)

    override def decryptAsBytes(reversiblyEncrypted: Crypted): PlainBytes = PlainBytes(reversiblyEncrypted.value.getBytes(StandardCharsets.UTF_8))
  }

  object AddEncEncrypter extends Encrypter with Decrypter {

    override def encrypt(plain: PlainContent): Crypted = plain match {
      case PlainText(value)  => Crypted(s"enc-$value")
      case PlainBytes(value) => Crypted(s"enc-${new String(value, StandardCharsets.UTF_8)}")
    }

    override def decrypt(reversiblyEncrypted: Crypted): PlainText =
      if (reversiblyEncrypted.value.startsWith("enc-")) PlainText(reversiblyEncrypted.value.substring(4))
      else throw new IllegalStateException("nope")

    override def decryptAsBytes(reversiblyEncrypted: Crypted): PlainBytes =
      if (reversiblyEncrypted.value.startsWith("enc-")) PlainBytes(reversiblyEncrypted.value.substring(4).getBytes(StandardCharsets.UTF_8))
      else throw new IllegalStateException("nope")
  }

}
