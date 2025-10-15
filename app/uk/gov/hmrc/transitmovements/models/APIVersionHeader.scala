/*
 * Copyright 2025 HM Revenue & Customs
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

import play.api.libs.json.*

enum APIVersionHeader(val value: String) {
  case V2_1 extends APIVersionHeader("2.1")
  case V3_0 extends APIVersionHeader("3.0")
}

object APIVersionHeader {

  implicit val jsonFormat: Format[APIVersionHeader] = new Format[APIVersionHeader] {
    override def writes(o: APIVersionHeader): JsValue             = JsString(o.value)
    override def reads(json: JsValue): JsResult[APIVersionHeader] =
      json
        .validate[String]
        .flatMap(
          APIVersionHeader
            .fromString(_)
            .map(JsSuccess(_))
            .getOrElse(JsError("Invalid API version"))
        )
  }

  def fromString(value: String): Option[APIVersionHeader] =
    values.find(_.value == value)
}
