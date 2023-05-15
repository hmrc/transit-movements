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

import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.libs.json.JsString
import play.api.libs.json.JsSuccess

class MessageSenderSpec extends AnyFreeSpec with Matchers with ScalaCheckDrivenPropertyChecks {

  "when MessageSender is serialized, return a JsString" in forAll(Gen.stringOfN(35, Gen.alphaNumChar)) {
    string =>
      MessageSender.messageSenderFormat.writes(MessageSender(string)) mustBe JsString(string)
  }

  "when JsString is deserialized, return a MovementReferenceNumber" in forAll(Gen.stringOfN(35, Gen.alphaNumChar)) {
    string =>
      MessageSender.messageSenderFormat.reads(JsString(string)) mustBe JsSuccess(MessageSender(string))
  }

}
