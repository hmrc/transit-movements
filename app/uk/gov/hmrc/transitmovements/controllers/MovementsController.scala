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
import cats.data.EitherT
import play.api.Logging
import play.api.http.MimeTypes
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import play.api.mvc.Request
import play.api.mvc.Result
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.play.http.HeaderCarrierConverter
import uk.gov.hmrc.transitmovements.controllers.errors.ConvertError
import uk.gov.hmrc.transitmovements.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovements.controllers.stream.StreamingParsers
import uk.gov.hmrc.transitmovements.models.ObjectStoreURI
import uk.gov.hmrc.transitmovements.models._
import uk.gov.hmrc.transitmovements.models.formats.PresentationFormats
import uk.gov.hmrc.transitmovements.models.mongo.UpdateMessageModel
import uk.gov.hmrc.transitmovements.models.requests.UpdateMessageMetadata
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse
import uk.gov.hmrc.transitmovements.models.responses.MovementResponse
import uk.gov.hmrc.transitmovements.models.responses.UpdateMovementResponse
import uk.gov.hmrc.transitmovements.repositories.MovementsRepository
import uk.gov.hmrc.transitmovements.services._

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.Future

@Singleton
class MovementsController @Inject() (
  cc: ControllerComponents,
  messageService: MessageService,
  movementFactory: MovementFactory,
  repo: MovementsRepository,
  movementsXmlParsingService: MovementsXmlParsingService,
  messagesXmlParsingService: MessagesXmlParsingService,
  objectStoreService: ObjectStoreService
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
    with ObjectStoreURIHelpers {

  def createMovement(eori: EORINumber, movementType: MovementType) =
    contentTypeRoute {
      case Some(_) =>
        movementType match {
          case MovementType.Arrival   => createArrival(eori)
          case MovementType.Departure => createDeparture(eori)
        }
      case None => createEmptyMovement(eori, movementType)
    }

  private def createArrival(eori: EORINumber): Action[Source[ByteString, _]] = Action.streamWithSize {
    (request: Request[Source[ByteString, _]], size: Long) =>
      implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
      (for {
        arrivalData <- movementsXmlParsingService.extractArrivalData(request.body).asPresentation
        received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
        // TODO: Create empty movement then create message -- avoids creating IDs here.
        movementId = movementFactory.generateId()
        message <- messageService
          .create(movementId, MessageType.ArrivalNotification, arrivalData.generationDate, received, None, request.body, size, MessageStatus.Processing)
          .asPresentation
        movement = movementFactory.createArrival(movementId, eori, MovementType.Arrival, arrivalData, message, received, received)
        _ <- repo.insert(movement).asPresentation
      } yield MovementResponse(movement._id, Some(movement.messages.head.id)))
        .fold[Result](baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)), response => Ok(Json.toJson(response)))
  }

  private def createDeparture(eori: EORINumber): Action[Source[ByteString, _]] = Action.streamWithSize {
    (request: Request[Source[ByteString, _]], size: Long) =>
      implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
      (for {
        declarationData <- movementsXmlParsingService.extractDeclarationData(request.body).asPresentation
        received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
        // TODO: Create empty movement then create message -- avoids creating IDs here.
        movementId = movementFactory.generateId()
        message <- messageService
          .create(movementId, MessageType.DeclarationData, declarationData.generationDate, received, None, request.body, size, MessageStatus.Processing)
          .asPresentation
        movement = movementFactory.createDeparture(movementId, eori, MovementType.Departure, declarationData, message, received, received)
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
      val message  = messageService.createEmpty(Some(messageType), received)
      val movement = movementFactory.createEmptyMovement(eori, movementType, message, received, received)
      (for {
        _ <- repo.insert(movement).asPresentation
      } yield MovementResponse(movement._id, Some(movement.messages.head.id))).fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        response => Ok(Json.toJson(response))
      )
  }

  // PATCH method for updating a specific message
  def updateMessage(eori: EORINumber, movementType: MovementType, movementId: MovementId, messageId: MessageId): Action[JsValue] =
    Action.async(parse.json) {
      implicit request =>
        (for {
          updateMessageMetadata <- require[UpdateMessageMetadata](request.body)
          maybeMovementToUpdate <- repo.getMovementWithoutMessages(eori, movementId, movementType).asPresentation
          movementToUpdate      <- ensureMovement(maybeMovementToUpdate, movementId)
          _                     <- updateMetadataIfRequired(movementId, movementType, movementToUpdate, updateMessageMetadata.objectStoreURI)
          _ <- repo
            .updateMessage(
              movementId,
              messageId,
              UpdateMessageModel(updateMessageMetadata.objectStoreURI, None, updateMessageMetadata.status),
              OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
            )
            .asPresentation
        } yield Ok)
          .valueOr[Result](
            presentationError => Status(presentationError.code.statusCode)(Json.toJson(presentationError))
          )
    }

  def updateMovement(movementId: MovementId, triggerId: Option[MessageId] = None): Action[Source[ByteString, _]] =
    contentTypeRoute {
      case Some(_) => updateMovementSmallMessage(movementId, triggerId)
      case None    => updateMovementLargeMessage(movementId, triggerId)
    }

  def updateMovementLargeMessage(movementId: MovementId, triggerId: Option[MessageId] = None): Action[AnyContent] = Action.async(parse.anyContent) {
    implicit request =>
      (for {
        messageType                 <- extract(request.headers).asPresentation
        objectStoreURI              <- extractObjectStoreURI(request.headers)
        objectStoreResourceLocation <- extractResourceLocation(objectStoreURI)
        sourceFile                  <- objectStoreService.getObjectStoreFile(objectStoreResourceLocation).asPresentation
        messageData                 <- messagesXmlParsingService.extractMessageData(sourceFile, messageType).asPresentation
        received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
        message = messageService.createWithURI(
          messageType,
          messageData.generationDate,
          received,
          triggerId,
          objectStoreURI,
          MessageStatus.Received
        )
        _ <- repo.addMessage(movementId, message, messageData.mrn, received).asPresentation
      } yield message.id).fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        id => Ok(Json.toJson(UpdateMovementResponse(id)))
      )
  }

  private def updateMovementSmallMessage(movementId: MovementId, triggerId: Option[MessageId] = None): Action[Source[ByteString, _]] =
    Action.streamWithSize {
      (request: Request[Source[ByteString, _]], size: Long) =>
        {
          implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
          for {
            messageType <- extract(request.headers).asPresentation
            messageData <- messagesXmlParsingService.extractMessageData(request.body, messageType).asPresentation
            received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
            status   = if (MessageType.responseValues.exists(_.code == messageType.code)) MessageStatus.Received else MessageStatus.Processing
            message <- messageService
              .create(
                movementId,
                messageType,
                messageData.generationDate,
                received,
                triggerId,
                request.body,
                size,
                status
              )
              .asPresentation
            _ <- repo.addMessage(movementId, message, messageData.mrn, received).asPresentation
          } yield message.id
        }.fold[Result](
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
  ): Action[AnyContent] =
    Action.async {
      repo
        .getMessages(eoriNumber, movementId, movementType, receivedSince)
        .asPresentation
        .fold[Result](
          baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
          messages => if (messages.isEmpty) NotFound else Ok(Json.toJson(messages))
        )
    }

  private def ensureMovement(movement: Option[MovementWithoutMessages], movementId: MovementId): EitherT[Future, PresentationError, MovementWithoutMessages] =
    EitherT(
      Future.successful(movement.map(Right.apply).getOrElse(Left(PresentationError.notFoundError(s"Movement with ID ${movementId.value} was not found"))))
    )

  // Large Messages: Only perform this step if we haven't updated the movement EORI and we have an Object Store reference
  private def updateMetadataIfRequired(
    movementId: MovementId,
    movementType: MovementType,
    movementToUpdate: MovementWithoutMessages,
    objectStoreURI: Option[ObjectStoreURI]
  )(implicit hc: HeaderCarrier): EitherT[Future, PresentationError, Unit] =
    (movementToUpdate.movementEORINumber, objectStoreURI) match {
      case (None, Some(location)) =>
        for {
          resourceLocation <- extractResourceLocation(location)
          source           <- objectStoreService.getObjectStoreFile(resourceLocation).asPresentation
          extractedData    <- movementsXmlParsingService.extractData(movementType, source).asPresentation
          _ <- repo
            .updateMovementMetadata(movementId, Some(extractedData.movementEoriNumber), extractedData.movementReferenceNumber, extractedData.generationDate)
            .asPresentation
        } yield ()
      case _ => EitherT.rightT((): Unit)
    }

  def getMessageBody(
    eoriNumber: EORINumber,
    movementType: MovementType,
    movementId: MovementId,
    messageId: MessageId
  ): Action[AnyContent] =
    Action.async {
      implicit request =>
        (for {
          messageData <- repo.getSingleMessage(eoriNumber, movementId, messageId, movementType).asPresentation
          body        <- getBody(messageData)
        } yield Ok.chunked(body, Some(MimeTypes.XML))).valueOr[Result](
          baseError => Status(baseError.code.statusCode)(Json.toJson(baseError))
        )
    }

  def putMessageBody(
    eoriNumber: EORINumber,
    movementType: MovementType,
    movementId: MovementId,
    messageId: MessageId
  ): Action[Source[ByteString, _]] =
    Action.streamWithSize {
      (request: Request[Source[ByteString, _]], size: Long) =>
        implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
        (for {
          // check to see we have the message in the database before we do anything
          _      <- repo.getSingleMessage(eoriNumber, movementId, messageId, movementType).asPresentation
          update <- messageService.update(movementId, messageId, request.body, size, MessageStatus.Processing).asPresentation
          _ <- repo
            .updateMessage(
              movementId,
              messageId,
              update,
              OffsetDateTime.now(clock)
            )
            .asPresentation
        } yield NoContent).valueOr {
          baseError => Status(baseError.code.statusCode)(Json.toJson(baseError))
        }
    }

  private def getBody(messageResponse: Option[MessageResponse])(implicit hc: HeaderCarrier): EitherT[Future, PresentationError, Source[ByteString, _]] =
    messageResponse match {
      case Some(MessageResponse(_, _, _, Some(body), _, None)) => EitherT.rightT(Source.single(ByteString(body)))
      case Some(MessageResponse(_, _, _, None, _, Some(uri))) =>
        for {
          resourceLocation <- extractResourceLocation(ObjectStoreURI(uri.toString))
          stream           <- objectStoreService.getObjectStoreFile(resourceLocation).asPresentation
        } yield stream
      case _ => EitherT.leftT(PresentationError.notFoundError("Message not found"))
    }

  private def require[T: Reads](responseBody: JsValue): EitherT[Future, PresentationError, T] =
    EitherT {
      responseBody
        .validate[T]
        .map(
          t => Future.successful(Right(t))
        )
        .getOrElse {
          logger.error("Unable to parse unexpected request")
          Future.successful(Left(PresentationError.badRequestError("Could not parse the request")))
        }
    }

}
