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
import uk.gov.hmrc.transitmovements.models._
import uk.gov.hmrc.transitmovements.models.formats.PresentationFormats
import uk.gov.hmrc.transitmovements.models.responses.MovementResponse
import uk.gov.hmrc.transitmovements.models.responses.UpdateMovementResponse
import uk.gov.hmrc.transitmovements.repositories.MovementsRepository
import uk.gov.hmrc.transitmovements.services._
import uk.gov.hmrc.transitmovements.utils.PreMaterialisedFutureProvider

import java.net.URI
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MovementsController @Inject() (
  cc: ControllerComponents,
  messageFactory: MessageFactory,
  movementFactory: MovementFactory,
  repo: MovementsRepository,
  movementsXmlParsingService: MovementsXmlParsingService,
  messagesXmlParsingService: MessagesXmlParsingService,
  objectStoreService: ObjectStoreService,
  val preMaterialisedFutureProvider: PreMaterialisedFutureProvider
)(implicit
  val materializer: Materializer,
  clock: Clock,
  val temporaryFileCreator: TemporaryFileCreator
) extends BackendController(cc)
    with Logging
    with StreamingParsers
    with ConvertError
    with MessageTypeHeaderExtractor
    with PresentationFormats
    with ContentTypeRouting
    with ObjectStoreURIHeaderExtractor {

  def createMovement(eori: EORINumber, movementType: MovementType) =
    contentTypeRoute {
      case Some(_) =>
        movementType match {
          case MovementType.Arrival   => createArrival(eori)
          case MovementType.Departure => createDeparture(eori)
        }
      case None => createEmptyMovement(eori, movementType)
    }

  private def createArrival(eori: EORINumber): Action[Source[ByteString, _]] = Action.streamWithAwait {
    awaitFileWrite => implicit request =>
      (for {
        arrivalData <- movementsXmlParsingService.extractArrivalData(request.body).asPresentation
        _           <- awaitFileWrite
        received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
        message <- messageFactory
          .create(MessageType.ArrivalNotification, arrivalData.generationDate, received, None, request.body, MessageStatus.Processing)
          .asPresentation
        movement = movementFactory.createArrival(eori, MovementType.Arrival, arrivalData, message, received, received)
        _ <- repo.insert(movement).asPresentation
      } yield MovementResponse(movement._id, Some(movement.messages.head.id))).fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        response => Ok(Json.toJson(response))
      )
  }

  private def createDeparture(eori: EORINumber): Action[Source[ByteString, _]] = Action.streamWithAwait {
    awaitFileWrite => implicit request =>
      (for {
        declarationData <- movementsXmlParsingService.extractDeclarationData(request.body).asPresentation
        _               <- awaitFileWrite
        received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
        message <- messageFactory
          .create(MessageType.DeclarationData, declarationData.generationDate, received, None, request.body, MessageStatus.Processing)
          .asPresentation
        movement = movementFactory.createDeparture(eori, MovementType.Departure, declarationData, message, received, received)
        _ <- repo.insert(movement).asPresentation
      } yield MovementResponse(movement._id, Some(movement.messages.head.id))).fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        response => Ok(Json.toJson(response))
      )
  }

  private def createEmptyMovement(eori: EORINumber, movementType: MovementType): Action[AnyContent] = Action.async(parse.anyContent) {
    implicit request =>
      val received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
      val messageType = movementType match {
        case MovementType.Arrival   => MessageType.ArrivalNotification
        case MovementType.Departure => MessageType.DeclarationData
      }
      val message  = messageFactory.createEmptyMessage(messageType, received)
      val movement = movementFactory.createEmptyMovement(eori, movementType, message, received, received)

      (for {
        _ <- repo.insert(movement).asPresentation
      } yield MovementResponse(movement._id, Some(movement.messages.head.id))).fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        response => Ok(Json.toJson(response))
      )
  }

  def updateMovement(movementId: MovementId, triggerId: Option[MessageId] = None) =
    contentTypeRoute {
      case Some(_) => updateMovementSmallMessage(movementId, triggerId)
      case None    => updateMovementLargeMessage(movementId, triggerId)
    }

  def updateMovementLargeMessage(movementId: MovementId, triggerId: Option[MessageId] = None): Action[AnyContent] = Action.async(parse.anyContent) {
    implicit request =>
      (for {
        messageType    <- extract(request.headers).asPresentation
        objectStoreURL <- extractObjectStoreURI(request.headers).asPresentation
        objectStoreFilePath = if (objectStoreURL.contains("/")) objectStoreURL.split("/", 2).apply(1) else objectStoreURL
        sourceFile  <- objectStoreService.getObjectStoreFile(objectStoreFilePath).asPresentation
        messageData <- messagesXmlParsingService.extractMessageData(sourceFile, messageType).asPresentation
        received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
        message = messageFactory
          .createLargeMessage(
            messageType,
            messageData.generationDate,
            received,
            triggerId,
            new URI(objectStoreURL)
          )
        _ <- repo.updateMessages(movementId, message, messageData.mrn, received).asPresentation
      } yield message.id).fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        id => Ok(Json.toJson(UpdateMovementResponse(id)))
      )
  }

  private def updateMovementSmallMessage(movementId: MovementId, triggerId: Option[MessageId] = None): Action[Source[ByteString, _]] =
    Action.streamWithAwait {
      awaitFileWrite => implicit request =>
        (for {
          messageType <- extract(request.headers).asPresentation
          messageData <- messagesXmlParsingService.extractMessageData(request.body, messageType).asPresentation
          _           <- awaitFileWrite
          received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
          status   = if (MessageType.responseValues.exists(_.code == messageType.code)) MessageStatus.Received else MessageStatus.Processing
          message <- messageFactory
            .create(
              messageType,
              messageData.generationDate,
              received,
              triggerId,
              request.body,
              status
            )
            .asPresentation
          _ <- repo.updateMessages(movementId, message, messageData.mrn, received).asPresentation
        } yield message.id).fold[Result](
          baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
          id => Ok(Json.toJson(UpdateMovementResponse(id)))
        )
    }

  def getMovementsForEori(
    eoriNumber: EORINumber,
    movementType: MovementType,
    updatedSince: Option[OffsetDateTime] = None,
    movementEORI: Option[EORINumber] = None
  ): Action[AnyContent] = Action.async {
    repo
      .getMovements(eoriNumber, movementType, updatedSince, movementEORI)
      .asPresentation
      .fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        movements => if (movements.isEmpty) NotFound else Ok(Json.toJson(movements))
      )
  }

  def getMovementWithoutMessages(eoriNumber: EORINumber, movementType: MovementType, movementId: MovementId): Action[AnyContent] = Action.async {
    repo
      .getMovementWithoutMessages(eoriNumber, movementId, movementType)
      .asPresentation
      .fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        {
          case Some(arrivalWithoutMessages) => Ok(Json.toJson(arrivalWithoutMessages))
          case None                         => NotFound
        }
      )
  }

  def getMessage(eoriNumber: EORINumber, movementType: MovementType, movementId: MovementId, messageId: MessageId): Action[AnyContent] = Action.async {
    repo
      .getSingleMessage(eoriNumber, movementId, messageId, movementType)
      .asPresentation
      .fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        {
          case Some(message) => Ok(Json.toJson(message))
          case None          => NotFound
        }
      )
  }

  def getMessages(
    eoriNumber: EORINumber,
    movementType: MovementType,
    movementId: MovementId,
    receivedSince: Option[OffsetDateTime] = None
  ) =
    Action.async {
      repo
        .getMessages(eoriNumber, movementId, movementType, receivedSince)
        .asPresentation
        .fold[Result](
          baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
          messages => if (messages.isEmpty) NotFound else Ok(Json.toJson(messages))
        )
    }

}
