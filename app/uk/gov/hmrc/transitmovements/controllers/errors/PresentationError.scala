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

package uk.gov.hmrc.transitmovements.controllers.errors

import play.api.Logging
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.functional.syntax.unlift
import play.api.libs.json.OFormat
import play.api.libs.json.OWrites
import play.api.libs.json.Reads
import play.api.libs.json.__
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.transitmovements.models.LocalReferenceNumber
import uk.gov.hmrc.transitmovements.models.formats.CommonFormats

object PresentationError extends CommonFormats with Logging {

  val MessageFieldName = "message"
  val CodeFieldName    = "code"

  def forbiddenError(message: String): PresentationError =
    StandardError(message, ErrorCode.Forbidden)

  def badRequestError(message: String): PresentationError = {
    logger.warn(s"Unable to parse the XML: $message")
    StandardError(message, ErrorCode.BadRequest)
  }

  def notAcceptableError(message: String): PresentationError =
    StandardError(message, ErrorCode.NotAcceptable)

  def unsupportedMediaTypeError(message: String): PresentationError =
    StandardError(message, ErrorCode.UnsupportedMediaType)

  def notFoundError(message: String): PresentationError =
    StandardError(message, ErrorCode.NotFound)

  def conflictError(message: String): PresentationError =
    StandardError(message, ErrorCode.Conflict)

  def duplicateLRNError(message: String, lrn: LocalReferenceNumber): PresentationError =
    DuplicateLRNError(message, ErrorCode.Conflict, lrn)

  def upstreamServiceError(
    message: String = "Internal server error",
    code: ErrorCode = ErrorCode.InternalServerError,
    cause: UpstreamErrorResponse
  ): PresentationError =
    UpstreamServiceError(message, code, cause)

  def internalServiceError(
    message: String = "Internal server error",
    code: ErrorCode = ErrorCode.InternalServerError,
    cause: Option[Throwable] = None
  ): PresentationError =
    InternalServiceError(message, code, cause)

  def unapply(error: PresentationError): Option[(String, ErrorCode)] = Some((error.message, error.code))

  private val baseErrorWrites0: OWrites[PresentationError] =
    (
      (__ \ MessageFieldName).write[String] and
        (__ \ CodeFieldName).write[ErrorCode]
    )(unlift(PresentationError.unapply))

  implicit val standardErrorReads: Reads[StandardError] =
    (
      (__ \ MessageFieldName).read[String] and
        (__ \ CodeFieldName).read[ErrorCode]
    )(StandardError.apply)

  implicit val duplicateLRNErrorFormat: OFormat[DuplicateLRNError] =
    (
      (__ \ MessageFieldName).format[String] and
        (__ \ CodeFieldName).format[ErrorCode] and
        (__ \ "lrn").format[LocalReferenceNumber]
    )(DuplicateLRNError.apply, v => (v.message, v.code, v.lrn))

  implicit val baseErrorWrites: OWrites[PresentationError] = OWrites {
    case duplicateLRNError: DuplicateLRNError => duplicateLRNErrorFormat.writes(duplicateLRNError)
    case baseError                            => baseErrorWrites0.writes(baseError)
  }

}

sealed abstract class PresentationError extends Product with Serializable {
  def message: String
  def code: ErrorCode
}

case class StandardError(message: String, code: ErrorCode) extends PresentationError

case class DuplicateLRNError(message: String, code: ErrorCode, lrn: LocalReferenceNumber) extends PresentationError

case class UpstreamServiceError(
  message: String = "Internal server error",
  code: ErrorCode = ErrorCode.InternalServerError,
  cause: UpstreamErrorResponse
) extends PresentationError

object UpstreamServiceError {

  def causedBy(cause: UpstreamErrorResponse): PresentationError =
    PresentationError.upstreamServiceError(cause = cause)
}

case class InternalServiceError(
  message: String = "Internal server error",
  code: ErrorCode = ErrorCode.InternalServerError,
  cause: Option[Throwable] = None
) extends PresentationError

object InternalServiceError {

  def causedBy(cause: Throwable): PresentationError =
    PresentationError.internalServiceError(cause = Some(cause))
}
