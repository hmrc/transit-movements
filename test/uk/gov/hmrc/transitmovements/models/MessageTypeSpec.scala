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

package uk.gov.hmrc.transitmovements.models

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.libs.json.JsError
import play.api.libs.json.JsNull
import play.api.libs.json.JsNumber
import play.api.libs.json.JsString
import play.api.libs.json.JsSuccess
import play.api.libs.json.Json
import uk.gov.hmrc.transitmovements.generators.ModelGenerators

class MessageTypeSpec extends AnyFreeSpec with Matchers with ScalaCheckDrivenPropertyChecks with ModelGenerators {

  "messageTypeReads" - {
    "reads should return the message type code only" in forAll(arbitrary[MessageType]) {
      messageType =>
        MessageType.messageTypeReads.reads(JsString(messageType.code)) mustBe JsSuccess(messageType)
    }

    "reads should return an error for an unsupported type" in forAll(Gen.oneOf(JsNull, JsNumber(1), Json.obj())) {
      badObject =>
        MessageType.messageTypeReads.reads(badObject) mustBe JsError(s"Invalid value: $badObject")
    }
  }

  "messageTypeWrites" - {
    "writes should only write the code as a JsString" in forAll(arbitrary[MessageType]) {
      messageType =>
        MessageType.messageTypeWrites.writes(messageType) mustBe JsString(messageType.code)
    }
  }

}
