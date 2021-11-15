/*
 * Copyright 2021 HM Revenue & Customs
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

package models
package request

import akka.stream.scaladsl.Source
import akka.util.ByteString
import models.values.CustomsOfficeNumber
import models.values.EoriNumber

import java.time.OffsetDateTime

/** Metadata parsed from the user's XML message
  * and a link to the XML in object-store
  */
case class DepartureMessageRequest(
  eoriNumber: EoriNumber,
  preparationDateTime: OffsetDateTime,
  officeOfDeparture: CustomsOfficeNumber,
  messageType: DepartureMessageType,
  messageData: Source[ByteString, _]
)
