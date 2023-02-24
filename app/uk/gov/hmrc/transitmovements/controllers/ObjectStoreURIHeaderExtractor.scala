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

package uk.gov.hmrc.transitmovements.controllers

import cats.data.EitherT
import play.api.mvc.Headers
import uk.gov.hmrc.transitmovements.config.Constants
import uk.gov.hmrc.transitmovements.controllers.errors.HeaderExtractError
import uk.gov.hmrc.transitmovements.controllers.errors.HeaderExtractError.NoHeaderFound

import scala.concurrent.Future

trait ObjectStoreURIHeaderExtractor {

  def extractObjectStoreURI(headers: Headers): EitherT[Future, HeaderExtractError, String] =
    EitherT {
      headers.get(Constants.ObjectStoreURI) match {
        case Some(headerValue) => Future.successful(Right(headerValue))
        case None              => Future.successful(Left(NoHeaderFound("Missing X-Object-Store-Uri header value")))
      }
    }
}
