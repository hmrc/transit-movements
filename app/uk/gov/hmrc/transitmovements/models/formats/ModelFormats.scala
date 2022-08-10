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

package uk.gov.hmrc.transitmovements.models.formats

import play.api.libs.json.Format
import play.api.libs.json.Json
import uk.gov.hmrc.mongo.play.json.formats.MongoBinaryFormats
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.formats.MongoUuidFormats
import uk.gov.hmrc.transitmovements.models.Departure
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.DepartureWithoutMessages
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageType

trait ModelFormats extends CommonFormats with MongoBinaryFormats.Implicits with MongoJavatimeFormats.Implicits with MongoUuidFormats.Implicits {

  implicit val eoriNumberFormat: Format[EORINumber]   = Json.valueFormat[EORINumber]
  implicit val messageIdFormat: Format[MessageId]     = Json.valueFormat[MessageId]
  implicit val departureIdFormat: Format[DepartureId] = Json.valueFormat[DepartureId]

  implicit val messageTypeFormat: Format[MessageType] = enumFormat(MessageType.values)(_.code)

  implicit val messageFormat: Format[Message]     = Json.format[Message]
  implicit val departureFormat: Format[Departure] = Json.format[Departure]

  implicit val departureWithoutMessagesFormat: Format[DepartureWithoutMessages] = Json.format[DepartureWithoutMessages]
}

object ModelFormats extends ModelFormats
