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

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.google.inject.Inject
import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.transitmovements.controllers.errors.ConvertError
import uk.gov.hmrc.transitmovements.controllers.stream.StreamingParsers
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.ObjectStoreSummaryResponse
import uk.gov.hmrc.transitmovements.models.ObjectStoreURI
import uk.gov.hmrc.transitmovements.services.ObjectStoreService

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// Temporary until we can remove the passing of Object Store URIs about
class ObjectStoreController @Inject() (cc: ControllerComponents, objectStoreService: ObjectStoreService)(implicit
  val materializer: Materializer,
  ec: ExecutionContext
) extends BackendController(cc)
    with ObjectStoreURIHelpers
    with ConvertError
    with StreamingParsers
    with Logging {

  def getObject(uri: ObjectStoreURI): Action[AnyContent] = Action.async {
    implicit request =>
      (for {
        resourceLocation <- extractResourceLocation(uri)
        objectStream     <- objectStoreService.getObjectStoreFile(resourceLocation).asPresentation
      } yield Ok.chunked(objectStream))
        .valueOrF(
          error => Future.successful(Status(error.code.statusCode)(Json.toJson(error)))
        )
  }

  def putObject(movementId: MovementId, messageId: MessageId): Action[Source[ByteString, _]] = Action.async(streamFromMemory) {
    implicit request =>
      objectStoreService
        .putObjectStoreFile(movementId, messageId, request.body)
        .map(
          summary => ObjectStoreSummaryResponse(summary)
        )
        .asPresentation
        .fold(
          error => Status(error.code.statusCode)(Json.toJson(error)),
          result => Created(Json.toJson(result))
        )
  }

}
