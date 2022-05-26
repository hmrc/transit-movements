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
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.MessageType
<<<<<<< Updated upstream
import uk.gov.hmrc.transitmovements.models.Movement
import uk.gov.hmrc.transitmovements.models.MovementId
=======
import uk.gov.hmrc.transitmovements.models.Departure
import uk.gov.hmrc.transitmovements.models.DepartureId
>>>>>>> Stashed changes
import uk.gov.hmrc.transitmovements.models.MovementMessage
import uk.gov.hmrc.transitmovements.models.MovementMessageId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.TriggerId

import java.net.URI
import java.time.OffsetDateTime

trait MongoFormats extends CommonFormats with MongoBinaryFormats.Implicits with MongoJavatimeFormats.Implicits with MongoUuidFormats.Implicits {

  implicit val eoriNumberFormat: Format[EORINumber]               = Json.valueFormat[EORINumber]
  implicit val movementMessageIdFormat: Format[MovementMessageId] = Json.valueFormat[MovementMessageId]
<<<<<<< Updated upstream
  implicit val movementIdFormat: Format[MovementId]               = Json.valueFormat[MovementId]
=======
  implicit val movementIdFormat: Format[DepartureId]               = Json.valueFormat[DepartureId]
  implicit val triggerIdFormat: Format[TriggerId]                 = Json.valueFormat[TriggerId]
>>>>>>> Stashed changes

//  val movementWrites: OWrites[Movement] = Json.writes[Movement]
//  implicit val movementReads: Reads[Movement]    = Json.reads[Movement]
//  implicit val movementFormat: Format[Movement]  = Format(movementReads, movementWrites)

  implicit val messageTypeFormat: Format[MessageType] = enumFormat(MessageType.values)(_.code)

//  implicit val movementMessageWrites: OWrites[MovementMessage] = Json.writes[MovementMessage]
//  implicit val movementMessageReads: Reads[MovementMessage]    = Json.reads[MovementMessage]
//  implicit val movementMessageFormat: Format[MovementMessage]  = Format(movementMessageReads, movementMessageWrites)
//
//  implicit val movementSeqMessageFormat: Format[Seq[MovementMessage]] = Json.format[Seq[MovementMessage]]

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

<<<<<<< Updated upstream
  implicit val movementReads: Reads[Movement] = (
    (JsPath \ "_id").read[MovementId] and
=======
  implicit val movementReads: Reads[Departure] = (
    (JsPath \ "_id").read[DepartureId] and
>>>>>>> Stashed changes
      (JsPath \ "enrollmentEORINumber").read[EORINumber] and
      (JsPath \ "movementEORINumber").read[EORINumber] and
      (JsPath \ "movementReferenceNumber").readNullable[MovementReferenceNumber] and
      (JsPath \ "created").read[OffsetDateTime] and
      (JsPath \ "updated").read[OffsetDateTime] and
      (JsPath \ "messages").read[Seq[MovementMessage]]
<<<<<<< Updated upstream
  )(Movement.apply _)

  implicit val movementWrites: Writes[Movement] = (
    (JsPath \ "_id").write[MovementId] and
=======
  )(Departure.apply _)

  implicit val movementWrites: Writes[Departure] = (
    (JsPath \ "_id").write[DepartureId] and
>>>>>>> Stashed changes
      (JsPath \ "enrollmentEORINumber").write[EORINumber] and
      (JsPath \ "movementEORINumber").write[EORINumber] and
      (JsPath \ "movementReferenceNumber").writeNullable[MovementReferenceNumber] and
      (JsPath \ "created").write[OffsetDateTime] and
      (JsPath \ "updated").write[OffsetDateTime] and
      (JsPath \ "messages").write[Seq[MovementMessage]]
<<<<<<< Updated upstream
  )(unlift(Movement.unapply))

  implicit val movementFormat: Format[Movement] = Format(movementReads, movementWrites)
=======
  )(unlift(Departure.unapply))

  implicit val movementFormat: Format[Departure] = Format(movementReads, movementWrites)
>>>>>>> Stashed changes

}

object MongoFormats extends MongoFormats
