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

package uk.gov.hmrc.transitmovements.models.mongo.read

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse

class MongoMessageMetadataAndBodySpec extends AnyFreeSpec with Matchers with ScalaCheckDrivenPropertyChecks with ModelGenerators {

  "MongoMessageMetadataAndBody#asMessageResponse" - {

    "should convert to a MessageResponse with a body if the body was included" in forAll(arbitrary[MongoMessageMetadataAndBody], Gen.alphaNumStr) {
      (message, body) =>
        message.copy(body = Some(SensitiveString(body))).asMessageResponse mustBe MessageResponse(
          message.id,
          message.received,
          message.messageType,
          Some(body),
          message.status,
          message.triggerId,
          message.uri
        )
    }

    "should convert to a MessageResponse without a body if the body was included" in forAll(arbitrary[MongoMessageMetadataAndBody]) {
      message =>
        message.copy(body = None).asMessageResponse mustBe MessageResponse(
          message.id,
          message.received,
          message.messageType,
          None,
          message.status,
          message.triggerId,
          message.uri
        )
    }
  }

}
