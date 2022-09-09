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
import akka.stream.scaladsl.FileIO
import akka.stream.scaladsl.Source
import akka.util.ByteString
import play.api.libs.Files.TemporaryFileCreator
import play.api.mvc.Action
import play.api.mvc.ControllerComponents
import play.api.mvc.Result
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MovementId
import play.api.libs.json.Json
import uk.gov.hmrc.transitmovements.controllers.errors.ConvertError
import uk.gov.hmrc.transitmovements.controllers.stream.StreamingParsers
import uk.gov.hmrc.transitmovements.repositories.DeparturesRepository
import uk.gov.hmrc.transitmovements.services.MessageFactory
import uk.gov.hmrc.transitmovements.services.MessagesXmlParsingService

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MovementsController @Inject() (
  cc: ControllerComponents,
  factory: MessageFactory,
  repo: DeparturesRepository,
  xmlParsingService: MessagesXmlParsingService,
  val temporaryFileCreator: TemporaryFileCreator
)(implicit
  val materializer: Materializer
) extends BackendController(cc)
    with StreamingParsers
    with TemporaryFiles
    with ConvertError
    with MessageTypeHeaderExtractor {

  def updateMovement(movementId: MovementId, triggerId: MessageId): Action[Source[ByteString, _]] = Action.async(streamFromMemory) {
    implicit request =>
      withTemporaryFile {
        (temporaryFile, source) =>
          (for {
            messageType <- extract(request.headers).asPresentation // need to write unit test for this
            messageData <- xmlParsingService.extractMessageData(source, messageType).asPresentation
            fileSource = FileIO.fromPath(temporaryFile)
            message <- factory.create(messageType, messageData.generationDate, Some(triggerId), fileSource).asPresentation
            result  <- repo.updateMessages(DepartureId(movementId.value), message, messageData.mrn).asPresentation
          } yield result).fold[Result](
            baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
            _ => Ok
          )
      }.toResult
  }

}
