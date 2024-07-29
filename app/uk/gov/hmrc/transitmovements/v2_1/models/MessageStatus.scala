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

package uk.gov.hmrc.transitmovements.v2_1.models

import play.api.libs.json.JsError
import play.api.libs.json.JsString
import play.api.libs.json.JsSuccess
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.Writes

sealed trait MessageStatus extends Product with Serializable

object MessageStatus {
  final case object Received extends MessageStatus

  final case object Pending extends MessageStatus

  final case object Processing extends MessageStatus

  final case object Success extends MessageStatus

  final case object Failed extends MessageStatus

  val statusValues: Seq[MessageStatus] = Seq(Received, Pending, Processing, Success, Failed)

  def find(value: String): Option[MessageStatus] = statusValues.find(
    status => value == status.toString
  )

  implicit val messageStatusWrites = new Writes[MessageStatus] {

    def writes(status: MessageStatus) = Json.toJson(status.toString)
  }

  implicit val statusReads: Reads[MessageStatus] = Reads {
    case JsString(proposedStatus) =>
      find(proposedStatus)
        .map(
          x => JsSuccess(x)
        )
        .getOrElse(JsError("Invalid message status"))
    case _ => JsError("Invalid message status")
  }
}
