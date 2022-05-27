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

import play.api.libs.functional.syntax._
import play.api.libs.json.Format
import play.api.libs.json.JsPath
import play.api.libs.json.Json
import play.api.libs.json.OWrites
import play.api.libs.json.Reads
import play.api.libs.json.Writes
import uk.gov.hmrc.mongo.play.json.formats.MongoBinaryFormats
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.formats.MongoUuidFormats
import uk.gov.hmrc.transitmovements.models.Departure
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MovementMessage
import uk.gov.hmrc.transitmovements.models.MovementMessageId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.TriggerId

import java.net.URI
import java.time.OffsetDateTime

trait MongoFormats extends CommonFormats with MongoBinaryFormats.Implicits with MongoJavatimeFormats.Implicits with MongoUuidFormats.Implicits {

  implicit val eoriNumberFormat: Format[EORINumber]               = Json.valueFormat[EORINumber]
  implicit val movementMessageIdFormat: Format[MovementMessageId] = Json.valueFormat[MovementMessageId]
  implicit val departureIdFormat: Format[DepartureId]             = Json.valueFormat[DepartureId]
  implicit val triggerIdFormat: Format[TriggerId]                 = Json.valueFormat[TriggerId]

  implicit val messageTypeFormat: Format[MessageType] = enumFormat(MessageType.values)(_.code)

  implicit val movementMessageReads: Reads[MovementMessage] = (
    (JsPath \ "id").read[MovementMessageId] and
      (JsPath \ "received").read[OffsetDateTime] and
      (JsPath \ "generated").read[OffsetDateTime] and
      (JsPath \ "messageType").read[MessageType] and
      (JsPath \ "triggerId").readNullable[TriggerId] and
      (JsPath \ "url").readNullable[URI] and
      (JsPath \ "body").readNullable[String]
  )(MovementMessage.apply _)

  implicit val movementMessageWrites: OWrites[MovementMessage] = (
    (JsPath \ "id").write[MovementMessageId] and
      (JsPath \ "received").write[OffsetDateTime] and
      (JsPath \ "generated").write[OffsetDateTime] and
      (JsPath \ "messageType").write[MessageType] and
      (JsPath \ "triggerId").writeNullable[TriggerId] and
      (JsPath \ "url").writeNullable[URI] and
      (JsPath \ "body").writeNullable[String]
  )(unlift(MovementMessage.unapply))

  implicit val departureReads: Reads[Departure] = (
    (JsPath \ "_id").read[DepartureId] and
      (JsPath \ "enrollmentEORINumber").read[EORINumber] and
      (JsPath \ "movementEORINumber").read[EORINumber] and
      (JsPath \ "movementReferenceNumber").readNullable[MovementReferenceNumber] and
      (JsPath \ "created").read[OffsetDateTime] and
      (JsPath \ "updated").read[OffsetDateTime] and
      (JsPath \ "messages").read[Seq[MovementMessage]]
  )(Departure.apply _)

  implicit val departureWrites: Writes[Departure] = (
    (JsPath \ "_id").write[DepartureId] and
      (JsPath \ "enrollmentEORINumber").write[EORINumber] and
      (JsPath \ "movementEORINumber").write[EORINumber] and
      (JsPath \ "movementReferenceNumber").writeNullable[MovementReferenceNumber] and
      (JsPath \ "created").write[OffsetDateTime] and
      (JsPath \ "updated").write[OffsetDateTime] and
      (JsPath \ "messages").write[Seq[MovementMessage]]
  )(unlift(Departure.unapply))

  implicit val departureFormat: Format[Departure] = Format(departureReads, departureWrites)

}

object MongoFormats extends MongoFormats
