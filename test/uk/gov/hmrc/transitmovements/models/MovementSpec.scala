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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.JsString
import play.api.libs.json.Json
import uk.gov.hmrc.transitmovements.models.formats.PresentationFormats

import java.net.URI
import java.time.OffsetDateTime

class MovementSpec extends AnyFlatSpec with Matchers with PresentationFormats {

  "json formatted departure" should "be created correctly" in {

    val movement = Movement(
      MovementId("1"),
      MovementType.Departure,
      EORINumber("222"),
      Some(EORINumber("223")),
      Some(MovementReferenceNumber("333")),
      OffsetDateTime.now(),
      OffsetDateTime.now(),
      Vector(
        Message(
          id = MessageId("999"),
          received = OffsetDateTime.now(),
          generated = Some(OffsetDateTime.now()),
          messageType = Some(MessageType.ReleaseForTransit),
          triggerId = Some(MessageId("888")),
          uri = Some(URI.create("xyz")),
          body = Some("body"),
          size = Some(1L),
          status = Some(MessageStatus.Processing)
        )
      )
    )

    val result = Json.toJson[Movement](movement)

    (result \ "_id").get should be(JsString("1"))
    (result \ "movementEORINumber").get should be(JsString("223"))
    (result \ "movementEORINumber").get should be(JsString("223"))
    (result \ "messages" \\ "id").head should be(JsString("999"))
  }
}
