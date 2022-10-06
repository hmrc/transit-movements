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

import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import akka.stream.Materializer
import akka.stream.scaladsl.FileIO
import akka.stream.scaladsl.Source
import akka.util.ByteString
import play.api.Logging
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import play.api.mvc.Result
import play.api.libs.json.Json
import play.api.libs.Files.TemporaryFileCreator
import uk.gov.hmrc.transitmovements.controllers.errors.ConvertError
import uk.gov.hmrc.transitmovements.controllers.stream.StreamingParsers
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementType
import uk.gov.hmrc.transitmovements.services.MovementFactory
import uk.gov.hmrc.transitmovements.services.MovementsXmlParsingService
import uk.gov.hmrc.transitmovements.services.MessageFactory
import uk.gov.hmrc.transitmovements.models.formats.PresentationFormats
import uk.gov.hmrc.transitmovements.models.responses.DeclarationResponse
import uk.gov.hmrc.transitmovements.repositories.MovementsRepository

import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeparturesController @Inject() (
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

  def createDeparture(eori: EORINumber): Action[Source[ByteString, _]] = Action.async(streamFromMemory) {
    implicit request =>
      withTemporaryFile {
        (temporaryFile, source) =>
          (for {
            declarationData <- xmlParsingService.extractDeclarationData(source).asPresentation
            fileSource = FileIO.fromPath(temporaryFile)
            message <- messageFactory.create(MessageType.DeclarationData, declarationData.generationDate, None, fileSource).asPresentation
            movement = movementFactory.create(eori, MovementType.Departure, declarationData, message)
            _ <- repo.insert(movement).asPresentation
          } yield DeclarationResponse(movement._id, movement.messages.head.id)).fold[Result](
            baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
            response => Ok(Json.toJson(response))
          )
      }.toResult
  }

  def getDepartureWithoutMessages(eoriNumber: EORINumber, movementId: MovementId): Action[AnyContent] = Action.async {
    repo
      .getDepartureWithoutMessages(eoriNumber, movementId)
      .asPresentation
      .fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        {
          case Some(departureWithoutMessages) => Ok(Json.toJson(departureWithoutMessages))
          case None                           => NotFound
        }
      )
  }

  def getDepartureMessages(eoriNumber: EORINumber, movementId: MovementId, receivedSince: Option[OffsetDateTime] = None) = Action.async {
    repo
      .getMessages(eoriNumber, movementId, MovementType.Departure, receivedSince)
      .asPresentation
      .fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        {
          case Some(departureMessages) => Ok(Json.toJson(departureMessages))
          case None                    => NotFound
        }
      )
  }

  def getMessage(eoriNumber: EORINumber, movementId: MovementId, messageId: MessageId): Action[AnyContent] = Action.async {
    repo
      .getSingleMessage(eoriNumber, movementId, messageId, MovementType.Departure)
      .asPresentation
      .fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        {
          case Some(message) => Ok(Json.toJson(message))
          case None          => NotFound
        }
      )
  }

  def getDeparturesForEori(eoriNumber: EORINumber): Action[AnyContent] = Action.async {
    repo
      .getDepartures(eoriNumber)
      .asPresentation
      .fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        {
          case Some(message) => Ok(Json.toJson(message))
          case None          => NotFound
        }
      )
  }
}
