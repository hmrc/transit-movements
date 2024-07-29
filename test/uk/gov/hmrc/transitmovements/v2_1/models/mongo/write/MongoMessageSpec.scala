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

package uk.gov.hmrc.transitmovements.v2_1.models.mongo.write

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.transitmovements.v2_1.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.v2_1.models.Message

class MongoMessageSpec extends AnyFreeSpec with Matchers with ScalaCheckDrivenPropertyChecks with ModelGenerators {

  "MongoMessage#from" - {
    "should convert to an appropriate MongoMessage with a sensitive string if the body is defined" in forAll(arbitrary[Message], Gen.alphaNumStr) {
      (message, body) =>
        MongoMessage.from(message.copy(body = Some(body))) mustBe MongoMessage(
          message.id,
          message.received,
          message.generated,
          message.messageType,
          message.triggerId,
          message.uri,
          Some(SensitiveString(body)),
          message.size,
          message.status
        )
    }

    "should convert to an appropriate MongoMessage with no sensitive string if the body is not defined" in forAll(arbitrary[Message]) {
      message =>
        MongoMessage.from(message.copy(body = None)) mustBe MongoMessage(
          message.id,
          message.received,
          message.generated,
          message.messageType,
          message.triggerId,
          message.uri,
          None,
          message.size,
          message.status
        )
    }
  }

}
