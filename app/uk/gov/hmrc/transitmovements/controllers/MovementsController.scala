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

package uk.gov.hmrc.transitmovements.controllers

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.ControllerComponents
import play.api.mvc.Result
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.transitmovements.controllers.errors.ConvertError
import uk.gov.hmrc.transitmovements.controllers.stream.StreamingParsers
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.responses.UpdateMovementResponse
import uk.gov.hmrc.transitmovements.repositories.MovementsRepository
import uk.gov.hmrc.transitmovements.services.MessageFactory
import uk.gov.hmrc.transitmovements.services.MessagesXmlParsingService
import uk.gov.hmrc.transitmovements.utils.PreMaterialisedFutureProvider

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MovementsController @Inject() (
  cc: ControllerComponents,
  factory: MessageFactory,
  repo: MovementsRepository,
  xmlParsingService: MessagesXmlParsingService,
  val preMaterialisedFutureProvider: PreMaterialisedFutureProvider
)(implicit
  val materializer: Materializer,
  val temporaryFileCreator: TemporaryFileCreator
) extends BackendController(cc)
    with StreamingParsers
    with ConvertError
    with MessageTypeHeaderExtractor {

  def updateMovement(movementId: MovementId, triggerId: Option[MessageId] = None): Action[Source[ByteString, _]] =
    Action.streamWithAwait {
      awaitFileWrite => implicit request =>
        (for {
          messageType <- extract(request.headers).asPresentation
          messageData <- xmlParsingService.extractMessageData(request.body, messageType).asPresentation
          _           <- awaitFileWrite
          message <- factory
            .create(
              messageType,
              messageData.generationDate,
              triggerId,
              request.body
            )
            .asPresentation
          _ <- repo.updateMessages(movementId, message, messageData.mrn).asPresentation
        } yield message.id).fold[Result](
          baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
          id => Ok(Json.toJson(UpdateMovementResponse(id)))
        )
    }

}
