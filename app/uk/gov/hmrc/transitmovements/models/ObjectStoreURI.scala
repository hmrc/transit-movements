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

import play.api.libs.json.Format
import play.api.libs.json.Json
import uk.gov.hmrc.transitmovements.config.Constants

import scala.util.matching.Regex

object ObjectStoreURI {

  private val alternative = "common-transit-convention-traders"

  // The URI consists of the service name in the first part of the path, followed
  // by the location of the object in the context of that service. As this service
  // targets transit-movements' objects exclusively (after transitioning away from
  // common-transit-convention-traders), we ensure  the URI is targeting that context
  // or the legacy one. This regex ensures that this is the case.
  lazy val expectedUriPattern: Regex = s"^(${Constants.ObjectStoreOwner}|$alternative)\\/(.+)$$".r

  implicit val objectStoreURIformat: Format[ObjectStoreURI] = Json.valueFormat[ObjectStoreURI]
}

case class ObjectStoreURI(value: String) extends AnyVal {

  def asResourceLocation: Option[ObjectStoreResourceLocation] =
    ObjectStoreURI.expectedUriPattern
      .findFirstMatchIn(value)
      .map(_.group(2))
      .map(ObjectStoreResourceLocation.apply)

}
