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
import uk.gov.hmrc.objectstore.client.Path
import org.scalatest.matchers.should.Matchers
import uk.gov.hmrc.transitmovements.generators.ModelGenerators

import java.util.UUID.randomUUID

class ObjectStoreURISpec extends AnyFlatSpec with Matchers with ModelGenerators {

  "a valid object store URI" should "return valid path" in {
    val filePath       = "common-transit-convention-traders/movements/abc.xml"
    val objectStoreURI = ObjectStoreURI(filePath)
    objectStoreURI.path should be(Some(s"movements/abc.xml"))
  }

  "an invalid object store URI" should "return None" in {
    val filePath =
      Path.Directory(s"movements/${arbitraryMovementId.arbitrary.sample.get}").file(randomUUID.toString).asUri
    val objectStoreURI = ObjectStoreURI(filePath)
    objectStoreURI.path should be(None)
  }
}
