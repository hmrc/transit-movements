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

package controllers

import models.errors._
import play.api.Logging

import scala.concurrent.Future

trait ErrorLogging { self: Logging =>
  def logServiceError[A](action: String, result: Either[TransitMovementError, A]): Future[Unit] = {
    result.fold(
      {
        case NotFoundError(_) =>
          Future.unit
        case BadRequestError(message) =>
          Future.successful(logger.error(s"Error in request data: ${message}"))
        case error @ XmlValidationError(_, _) =>
          Future.successful(logger.error(s"Error when $action: ${error.message}"))
        case UpstreamServiceError(_, cause) =>
          Future.successful(logger.error("Error when calling upstream service", cause))
        case InternalServiceError(_, Some(cause)) =>
          Future.successful(logger.error(s"Error when $action", cause))
        case InternalServiceError(message, None) =>
          Future.successful(logger.error(s"Error when $action: ${message}"))
      },
      _ => Future.unit
    )
  }
}
