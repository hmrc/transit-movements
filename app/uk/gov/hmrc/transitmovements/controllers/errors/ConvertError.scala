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
import uk.gov.hmrc.transitmovements.services.errors.MongoError.OtherError
import uk.gov.hmrc.transitmovements.services.errors.ParseError
import uk.gov.hmrc.transitmovements.services.errors.ParseError.BadDateTime
import uk.gov.hmrc.transitmovements.services.errors.ParseError.NoElementFound
import uk.gov.hmrc.transitmovements.services.errors.ParseError.TooManyElementsFound
import uk.gov.hmrc.transitmovements.services.errors.ParseError.Unknown

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait ConvertError {

  implicit class ErrorConverter[E, A](value: EitherT[Future, E, A]) {

    def asBaseError(implicit c: Converter[E], ec: ExecutionContext): EitherT[Future, BaseError, A] =
      value.leftMap(c.convert)
  }

  sealed trait Converter[E] {
    def convert(input: E): BaseError
  }

  implicit val parseErrorConverter = new Converter[ParseError] {

    def convert(parseError: ParseError): BaseError = parseError match {
      case NoElementFound(element)       => BaseError.badRequestError(s"Element $element not found")
      case TooManyElementsFound(element) => BaseError.badRequestError(s"Found too many elements of type $element")
      case BadDateTime(element, ex)      => BaseError.badRequestError(s"Could not parse datetime for $element: ${ex.getMessage}")
      case Unknown(ex)                   => BaseError.internalServiceError(cause = ex)
    }

  }

  implicit val mongoErrorConverter = new Converter[MongoError] {

    def convert(mongoError: MongoError): BaseError = mongoError match {
      case OtherError(ex) => BaseError.internalServiceError(cause = ex)
    }
  }

}