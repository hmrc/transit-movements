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

package uk.gov.hmrc.transitmovements.v2_1.controllers.errors

import cats.data.EitherT
import play.api.Logging
import uk.gov.hmrc.transitmovements.v2_1.services.errors.MongoError
import uk.gov.hmrc.transitmovements.v2_1.services.errors.ObjectStoreError
import uk.gov.hmrc.transitmovements.v2_1.services.errors.ObjectStoreError.FileNotFound
import uk.gov.hmrc.transitmovements.v2_1.services.errors.ParseError
import uk.gov.hmrc.transitmovements.v2_1.services.errors.StreamError

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait ConvertError {
  self: Logging =>

  implicit class ErrorConverter[E, A](value: EitherT[Future, E, A]) {

    def asPresentation(implicit c: Converter[E], ec: ExecutionContext): EitherT[Future, PresentationError, A] =
      value.leftMap(c.convert).leftSemiflatTap {
        case InternalServiceError(message, _, cause) =>
          val causeText = cause
            .map {
              ex =>
                s"""
                |Message: ${ex.getMessage}
                |Trace: ${ex.getStackTrace.mkString(System.lineSeparator())}
                |""".stripMargin
            }
            .getOrElse("No exception is available")
          logger.error(s"""Internal Server Error: $message
               |
               |$causeText""".stripMargin)
          Future.successful(())
        case _ => Future.successful(())
      }
  }

  sealed trait Converter[E] {
    def convert(input: E): PresentationError
  }

  implicit val parseErrorConverter: Converter[ParseError] = new Converter[ParseError] {
    import uk.gov.hmrc.transitmovements.v2_1.services.errors.ParseError._

    def convert(parseError: ParseError): PresentationError = parseError match {
      case NoElementFound(element)       => PresentationError.badRequestError(s"Element $element not found")
      case TooManyElementsFound(element) => PresentationError.badRequestError(s"Found too many elements of type $element")
      case BadDateTime(element, ex)      => PresentationError.badRequestError(s"Could not parse datetime for $element: ${ex.getMessage}")
      case UnexpectedError(ex)           => PresentationError.internalServiceError(cause = ex)
    }

  }

  implicit val mongoErrorConverter: Converter[MongoError] = new Converter[MongoError] {
    import uk.gov.hmrc.transitmovements.v2_1.services.errors.MongoError._

    def convert(mongoError: MongoError): PresentationError = mongoError match {
      case UnexpectedError(ex)            => PresentationError.internalServiceError(cause = ex)
      case InsertNotAcknowledged(message) => PresentationError.internalServiceError(message = message)
      case UpdateNotAcknowledged(message) => PresentationError.internalServiceError(message = message)
      case DocumentNotFound(message)      => PresentationError.notFoundError(message = message)
    }
  }

  implicit val streamErrorConverter: Converter[StreamError] = new Converter[StreamError] {
    import uk.gov.hmrc.transitmovements.v2_1.services.errors.StreamError._

    def convert(streamError: StreamError): PresentationError = streamError match {
      case UnexpectedError(ex) => PresentationError.internalServiceError(cause = ex)
    }
  }

  implicit val objectStoreErrorConverter: Converter[ObjectStoreError] = new Converter[ObjectStoreError] {

    import uk.gov.hmrc.transitmovements.v2_1.services.errors.ObjectStoreError._

    def convert(objectStoreError: ObjectStoreError): PresentationError = objectStoreError match {
      case FileNotFound(fileLocation) => PresentationError.badRequestError(s"file not found at location: $fileLocation")
      case UnexpectedError(ex)        => PresentationError.internalServiceError(cause = ex)
    }
  }

  // Needed for the object store controller (pass by URL instead of header).
  val objectStoreErrorWithNotFoundConverter: Converter[ObjectStoreError] = new Converter[ObjectStoreError] {

    override def convert(input: ObjectStoreError): PresentationError = input match {
      case FileNotFound(fileLocation) => PresentationError.notFoundError(s"file not found at location: $fileLocation")
      case x                          => objectStoreErrorConverter.convert(x)
    }
  }

  // Needed for the message body controller
  val objectStoreErrorWithInternalServiceErrorConverter: Converter[ObjectStoreError] = new Converter[ObjectStoreError] {

    override def convert(input: ObjectStoreError): PresentationError = input match {
      case FileNotFound(fileLocation) => PresentationError.internalServiceError(s"file not found at location: $fileLocation", cause = None)
      case err                        => objectStoreErrorConverter.convert(err)
    }
  }

  implicit val messageTypeExtractErrorConverter = new Converter[MessageTypeExtractError] {
    import uk.gov.hmrc.transitmovements.v2_1.controllers.errors.MessageTypeExtractError._

    def convert(messageTypeExtractError: MessageTypeExtractError): PresentationError = messageTypeExtractError match {
      case NoHeaderFound(message)      => PresentationError.badRequestError(message)
      case InvalidMessageType(message) => PresentationError.badRequestError(message)
    }
  }

}
