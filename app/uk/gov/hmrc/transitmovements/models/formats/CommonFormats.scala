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

import cats.data.NonEmptyList
import play.api.libs.functional.syntax.toInvariantFunctorOps
import play.api.libs.json.Format
import play.api.libs.json.JsError
import play.api.libs.json.JsResult
import play.api.libs.json.JsString
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.MovementType

object CommonFormats extends CommonFormats

trait CommonFormats {

  implicit def nonEmptyListFormat[A: Format]: Format[NonEmptyList[A]] =
    Format
      .of[List[A]]
      .inmap(
        NonEmptyList.fromListUnsafe,
        _.toList
      )

  implicit val mrnFormat: Format[MovementReferenceNumber] = Json.valueFormat[MovementReferenceNumber]
  implicit val eoriNumberFormat: Format[EORINumber]       = Json.valueFormat[EORINumber]
  implicit val movementTypeFormat: Format[MovementType]   = enumFormat(MovementType.movementTypes)(_.value)
  implicit val messageIdFormat: Format[MessageId]         = Json.valueFormat[MessageId]
  implicit val movementIdFormat: Format[MovementId]       = Json.valueFormat[MovementId]
  implicit val departureIdFormat: Format[DepartureId]     = Json.valueFormat[DepartureId]

  implicit val messageTypeFormat: Format[MessageType] = enumFormat(MessageType.values)(_.code)

  def enumFormat[A](values: Set[A])(getKey: A => String): Format[A] = new Format[A] {

    override def writes(a: A): JsValue =
      JsString(getKey(a))

    override def reads(json: JsValue): JsResult[A] = json match {
      case JsString(str) =>
        values
          .find(getKey(_) == str)
          .map(JsSuccess(_))
          .getOrElse(JsError("error.expected.validenumvalue"))
      case _ =>
        JsError("error.expected.enumstring")
    }
  }
}
