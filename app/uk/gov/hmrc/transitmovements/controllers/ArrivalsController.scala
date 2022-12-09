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
import play.api.Logging
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import play.api.mvc.Result
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.transitmovements.controllers.errors.ConvertError
import uk.gov.hmrc.transitmovements.controllers.stream.StreamingParsers
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementType
import uk.gov.hmrc.transitmovements.models.formats.PresentationFormats
import uk.gov.hmrc.transitmovements.models.responses.ArrivalNotificationResponse
import uk.gov.hmrc.transitmovements.repositories.MovementsRepository
import uk.gov.hmrc.transitmovements.services.MessageFactory
import uk.gov.hmrc.transitmovements.services.MovementFactory
import uk.gov.hmrc.transitmovements.services.MovementsXmlParsingService
import uk.gov.hmrc.transitmovements.utils.PreMaterialisedFutureProvider

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArrivalsController @Inject() (
  cc: ControllerComponents,
  movementFactory: MovementFactory,
  messageFactory: MessageFactory,
  repo: MovementsRepository,
  xmlParsingService: MovementsXmlParsingService,
  val preMaterialisedFutureProvider: PreMaterialisedFutureProvider
)(implicit
  val materializer: Materializer,
  clock: Clock,
  val temporaryFileCreator: TemporaryFileCreator
) extends BackendController(cc)
    with Logging
    with StreamingParsers
    with ConvertError
    with PresentationFormats {

  def createArrival(eori: EORINumber): Action[Source[ByteString, _]] = Action.streamWithAwait {
    awaitFileWrite => implicit request =>
      (for {
        arrivalData <- xmlParsingService.extractArrivalData(request.body).asPresentation
        _           <- awaitFileWrite
        received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
        message <- messageFactory.create(MessageType.ArrivalNotification, arrivalData.generationDate, received, None, request.body).asPresentation
        movement = movementFactory.createArrival(eori, MovementType.Arrival, arrivalData, message, received, received)
        _ <- repo.insert(movement).asPresentation
      } yield ArrivalNotificationResponse(movement._id, movement.messages.head.id)).fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        response => Ok(Json.toJson(response))
      )
  }

  def getArrivalsForEori(eoriNumber: EORINumber, updatedSince: Option[OffsetDateTime] = None): Action[AnyContent] = Action.async {
    repo
      .getMovements(eoriNumber, MovementType.Arrival, updatedSince)
      .asPresentation
      .fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        {
          case Some(message) => Ok(Json.toJson(message))
          case None          => NotFound
        }
      )
  }

  def getArrivalMessages(eoriNumber: EORINumber, movementId: MovementId, receivedSince: Option[OffsetDateTime] = None) = Action.async {
    repo
      .getMessages(eoriNumber, movementId, MovementType.Arrival, receivedSince)
      .asPresentation
      .fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        {
          case Some(arrivalMessages) => Ok(Json.toJson(arrivalMessages))
          case None                  => NotFound
        }
      )
  }

  def getArrivalWithoutMessages(eoriNumber: EORINumber, movementId: MovementId): Action[AnyContent] = Action.async {
    repo
      .getMovementWithoutMessages(eoriNumber, movementId, MovementType.Arrival)
      .asPresentation
      .fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        {
          case Some(arrivalWithoutMessages) => Ok(Json.toJson(arrivalWithoutMessages))
          case None                         => NotFound
        }
      )
  }

  def getMessage(eoriNumber: EORINumber, movementId: MovementId, messageId: MessageId): Action[AnyContent] = Action.async {
    repo
      .getSingleMessage(eoriNumber, movementId, messageId, MovementType.Arrival)
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
