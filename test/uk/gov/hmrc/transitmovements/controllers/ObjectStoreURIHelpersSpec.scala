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

import play.api.mvc.BaseController
import play.api.mvc.ControllerComponents
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.transitmovements.base.SpecBase
import uk.gov.hmrc.transitmovements.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.ObjectStoreResourceLocation
import uk.gov.hmrc.transitmovements.models.ObjectStoreURI

class ObjectStoreURIHelpersSpec extends SpecBase with ModelGenerators {

  class ObjectStoreURIHelpersImpl extends ObjectStoreURIHelpers with BaseController {
    override protected def controllerComponents: ControllerComponents = stubControllerComponents()
  }

  val objectStoreURIExtractor = new ObjectStoreURIHelpersImpl

  "extractResourceLocation" - {
    "if supplied object store uri is invalid, return BadRequestError" in {
      val result = objectStoreURIExtractor.extractResourceLocation(ObjectStoreURI("invalid"))

      whenReady(result.value) {
        _ mustBe Left(
          PresentationError.badRequestError(s"Provided Object Store URI is not owned by transit-movements")
        )
      }
    }

    "if supplied object store uri is valid, return Right" in {
      val filePath = "transit-movements/movements/movementId/abc.xml"

      val result = objectStoreURIExtractor.extractResourceLocation(ObjectStoreURI(filePath))

      whenReady(result.value) {
        _ mustBe Right(ObjectStoreResourceLocation("movements/movementId/abc.xml"))
      }
    }

  }

}
