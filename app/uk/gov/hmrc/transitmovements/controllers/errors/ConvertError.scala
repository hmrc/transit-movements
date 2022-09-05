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

package uk.gov.hmrc.transitmovements.controllers.errors

import cats.data.EitherT
import uk.gov.hmrc.transitmovements.services.errors.MongoError
import uk.gov.hmrc.transitmovements.services.errors.ParseError
import uk.gov.hmrc.transitmovements.services.errors.StreamError

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait ConvertError {

  implicit class ErrorConverter[E, A](value: EitherT[Future, E, A]) {

    def asPresentation(implicit c: Converter[E], ec: ExecutionContext): EitherT[Future, PresentationError, A] =
      value.leftMap(c.convert)
  }

  sealed trait Converter[E] {
    def convert(input: E): PresentationError
  }

  implicit val parseErrorConverter = new Converter[ParseError] {
    import uk.gov.hmrc.transitmovements.services.errors.ParseError._

    def convert(parseError: ParseError): PresentationError = parseError match {
      case NoElementFound(element)       => PresentationError.badRequestError(s"Element $element not found")
      case TooManyElementsFound(element) => PresentationError.badRequestError(s"Found too many elements of type $element")
      case BadDateTime(element, ex)      => PresentationError.badRequestError(s"Could not parse datetime for $element: ${ex.getMessage}")
      case UnexpectedError(ex)           => PresentationError.internalServiceError(cause = ex)
      case InvalidMessageType()          => PresentationError.badRequestError(s"No valid message type found")
    }

  }

  implicit val mongoErrorConverter = new Converter[MongoError] {
    import uk.gov.hmrc.transitmovements.services.errors.MongoError._

    def convert(mongoError: MongoError): PresentationError = mongoError match {
      case UnexpectedError(ex)            => PresentationError.internalServiceError(cause = ex)
      case InsertNotAcknowledged(message) => PresentationError.internalServiceError(message = message)
      case DocumentNotFound(message)      => PresentationError.badRequestError(message = message)
    }
  }

  implicit val streamErrorConverter = new Converter[StreamError] {
    import uk.gov.hmrc.transitmovements.services.errors.StreamError._

    def convert(streamError: StreamError): PresentationError = streamError match {
      case UnexpectedError(ex) => PresentationError.internalServiceError(cause = ex)
    }
  }

}
