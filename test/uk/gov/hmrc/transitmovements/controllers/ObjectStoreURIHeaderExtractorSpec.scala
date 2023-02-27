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

package uk.gov.hmrc.transitmovements.controllers

import uk.gov.hmrc.transitmovements.models.ObjectStoreURI
import play.api.mvc.Headers
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.transitmovements.base.SpecBase
import uk.gov.hmrc.transitmovements.controllers.errors.HeaderExtractError.NoHeaderFound
import uk.gov.hmrc.transitmovements.generators.ModelGenerators

import java.util.UUID.randomUUID

class ObjectStoreURIHeaderExtractorSpec extends SpecBase with ModelGenerators {

  class ObjectStoreURIHeaderExtractorImpl extends ObjectStoreURIHeaderExtractor

  val objectStoreURIExtractor = new ObjectStoreURIHeaderExtractorImpl

  "extractObjectStoreURI" - {

    "if object store uri header is not supplied, return NoHeaderFound" in {
      val noObjectStoreURIHeader = Headers("X-Message-Type" -> "IE015")

      val result = objectStoreURIExtractor.extractObjectStoreURI(noObjectStoreURIHeader)

      whenReady(result.value) {
        _ mustBe Left(NoHeaderFound("Missing X-Object-Store-Uri header value"))
      }
    }

    "if object store uri header is supplied, return Right" in {
      val filePath =
        Path.Directory(s"common-transit-convention-traders/movements/${arbitraryMovementId.arbitrary.sample.get}").file(randomUUID.toString).asUri
      val objectStoreURI            = ObjectStoreURI(filePath).value
      val validObjectStoreURIHeader = Headers("X-Object-Store-Uri" -> objectStoreURI)

      val result = objectStoreURIExtractor.extractObjectStoreURI(validObjectStoreURIHeader)

      whenReady(result.value) {
        _ mustBe Right(ObjectStoreURI(filePath))
      }
    }

  }

}
