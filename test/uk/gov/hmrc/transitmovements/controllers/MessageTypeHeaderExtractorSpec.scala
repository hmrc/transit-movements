/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.transitmovements.controllers

import org.scalacheck.Gen
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.mvc.Headers
import play.api.test.FakeHeaders
import uk.gov.hmrc.transitmovements.base.SpecBase
import uk.gov.hmrc.transitmovements.controllers.errors.HeaderExtractError.InvalidMessageType
import uk.gov.hmrc.transitmovements.controllers.errors.HeaderExtractError.NoHeaderFound
import uk.gov.hmrc.transitmovements.models.MessageType

class MessageTypeHeaderExtractorSpec extends SpecBase {

  class MessageTypeHeaderExtractorImpl extends MessageTypeHeaderExtractor

  val messageTypeExtractor = new MessageTypeHeaderExtractorImpl

  "extract" - {

    "if message type header is not supplied, return NoHeaderFound" in {
      val noMessageTypeHeader = Headers((HeaderNames.CONTENT_TYPE -> MimeTypes.XML))

      val result = messageTypeExtractor.extract(noMessageTypeHeader)

      whenReady(result.value) {
        _ mustBe Left(NoHeaderFound("Missing X-Message-Type header value"))
      }
    }

    "if message type supplied is invalid, return InvalidMessageType" in {
      val invalidMessageTypeHeader = Headers((HeaderNames.CONTENT_TYPE -> MimeTypes.XML), ("X-Message-Type" -> "invalid"))

      val result = messageTypeExtractor.extract(invalidMessageTypeHeader)

      whenReady(result.value) {
        _ mustBe Left(InvalidMessageType(s"Invalid X-Message-Type header value: invalid"))
      }
    }

    "if message type is valid, return Right" in {
      val messageType            = Gen.oneOf(MessageType.values).sample.get
      val validMessageTypeHeader = Headers((HeaderNames.CONTENT_TYPE -> MimeTypes.XML), ("X-Message-Type" -> s"${messageType.code}"))

      val result = messageTypeExtractor.extract(validMessageTypeHeader)

      whenReady(result.value) {
        _ mustBe Right(messageType)
      }
    }

  }

}
