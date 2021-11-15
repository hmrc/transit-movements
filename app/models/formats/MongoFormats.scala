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

package models.formats

import models.Departure
import models.Message
import models.MessageType
import models.values._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.play.json.formats.MongoBinaryFormats
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.formats.MongoUuidFormats
import uk.gov.hmrc.objectstore.client.Path

import java.time.OffsetDateTime

trait MongoFormats
  extends CommonFormats
  with MongoBinaryFormats.Implicits
  with MongoJavatimeFormats.Implicits
  with MongoUuidFormats.Implicits {

  implicit val departureIdFormat: Format[DepartureId] =
    Json.valueFormat[DepartureId]

  implicit val departureFormat: OFormat[Departure] = (
    (__ \ "_id").format[DepartureId] and
      (__ \ "eoriNumber").format[EoriNumber] and
      (__ \ "localReferenceNumber").format[LocalReferenceNumber] and
      (__ \ "movementReferenceNumber").formatNullable[MovementReferenceNumber] and
      (__ \ "createdAt").format[OffsetDateTime] and
      (__ \ "updatedAt").format[OffsetDateTime]
  )(Departure.apply, unlift(Departure.unapply))

  implicit val messageTypeFormat: Format[MessageType] =
    enumFormat(MessageType.values)(_.code)

  implicit val objectStoreFilePathFormat: Format[Path.File] =
    Format.of[String].inmap(Path.File(_), _.asUri)

  implicit val messageIdFormat: Format[MessageId] =
    Json.valueFormat[MessageId]

  implicit val messageFormat: OFormat[Message] = (
    (__ \ "_id").format[MessageId] and
      (__ \ "departureId").format[DepartureId] and
      (__ \ "preparedAt").format[OffsetDateTime] and
      (__ \ "messageType").format[MessageType] and
      (__ \ "messagePath").format[Path.File]
  )(Message.apply, unlift(Message.unapply))
}

object MongoFormats extends MongoFormats
