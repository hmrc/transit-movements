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
import play.api.Logging
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.ControllerComponents
import play.api.mvc.Result
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.transitmovements.controllers.errors.ConvertError
import uk.gov.hmrc.transitmovements.controllers.stream.StreamingParsers
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MovementType
import uk.gov.hmrc.transitmovements.models.formats.PresentationFormats
import uk.gov.hmrc.transitmovements.models.responses.ArrivalNotificationResponse
import uk.gov.hmrc.transitmovements.repositories.MovementsRepository
import uk.gov.hmrc.transitmovements.services.MessageFactory
import uk.gov.hmrc.transitmovements.services.MovementFactory
import uk.gov.hmrc.transitmovements.services.MovementsXmlParsingService

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArrivalsController @Inject() (
  cc: ControllerComponents,
  movementFactory: MovementFactory,
  messageFactory: MessageFactory,
  repo: MovementsRepository,
  xmlParsingService: MovementsXmlParsingService,
  val temporaryFileCreator: TemporaryFileCreator
)(implicit
  val materializer: Materializer
) extends BackendController(cc)
    with Logging
    with StreamingParsers
    with ConvertError
    with TemporaryFiles
    with PresentationFormats {

  def createArrival(eori: EORINumber): Action[Source[ByteString, _]] = Action.async(streamFromMemory) {
    implicit request =>
      withTemporaryFile {
        (temporaryFile, source) =>
          (for {
            arrivalData <- xmlParsingService.extractArrivalData(source).asPresentation
            fileSource = FileIO.fromPath(temporaryFile)
            message <- messageFactory.create(MessageType.ArrivalNotification, arrivalData.generationDate, None, fileSource).asPresentation
            movement = movementFactory.createArrival(eori, MovementType.Arrival, arrivalData, message)
            _ <- repo.insert(movement).asPresentation
          } yield ArrivalNotificationResponse(movement._id, movement.messages.head.id)).fold[Result](
            baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
            response => Ok(Json.toJson(response))
          )
      }.toResult
  }

}
