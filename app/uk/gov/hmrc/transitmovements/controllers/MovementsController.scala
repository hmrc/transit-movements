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
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import play.api.mvc.Result
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.play.http.HeaderCarrierConverter
import uk.gov.hmrc.transitmovements.controllers.errors.ConvertError
import uk.gov.hmrc.transitmovements.controllers.errors.MessageTypeExtractError
import uk.gov.hmrc.transitmovements.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovements.controllers.errors.MessageTypeExtractError.InvalidMessageType
import uk.gov.hmrc.transitmovements.controllers.stream.StreamingParsers
import uk.gov.hmrc.transitmovements.models._
import uk.gov.hmrc.transitmovements.models.formats.PresentationFormats
import uk.gov.hmrc.transitmovements.models.requests.UpdateMessageMetadata
import uk.gov.hmrc.transitmovements.models.requests.UpdateStatus
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse
import uk.gov.hmrc.transitmovements.models.responses.MovementResponse
import uk.gov.hmrc.transitmovements.models.responses.UpdateMovementResponse
import uk.gov.hmrc.transitmovements.repositories.MovementsRepository
import uk.gov.hmrc.transitmovements.services._
import uk.gov.hmrc.transitmovements.utils.StreamWithFile

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import scala.annotation.nowarn
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
    with StreamWithFile
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
    implicit request => size =>
      {
        for {
          arrivalData <- movementsXmlParsingService.extractArrivalData(request.body).asPresentation
          received   = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
          movementId = movementFactory.generateId()
          message <- messageService
            .create(movementId, MessageType.ArrivalNotification, arrivalData.generationDate, received, None, size, request.body, MessageStatus.Processing)
            .asPresentation
          movement = movementFactory.createArrival(movementId, eori, MovementType.Arrival, arrivalData, message, received, received)
          _ <- repo.insert(movement).asPresentation
        } yield MovementResponse(movement._id, Some(movement.messages.head.id))
      }.fold[Result](baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)), response => Ok(Json.toJson(response)))
  }

  private def createDeparture(eori: EORINumber): Action[Source[ByteString, _]] = Action.streamWithSize {
    implicit request => size =>
      {
        implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
        for {
          declarationData <- movementsXmlParsingService.extractDeclarationData(request.body).asPresentation
          received   = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
          movementId = movementFactory.generateId()
          message <- messageService
            .create(movementId, MessageType.DeclarationData, declarationData.generationDate, received, None, size, request.body, MessageStatus.Processing)
            .asPresentation
          movement = movementFactory.createDeparture(movementId, eori, MovementType.Departure, declarationData, message, received, received)
          _ <- repo.insert(movement).asPresentation
        } yield MovementResponse(movement._id, Some(movement.messages.head.id))
      }.fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        response => Ok(Json.toJson(response))
      )
  }

  private def createEmptyMovement(eori: EORINumber, movementType: MovementType): Action[AnyContent] = Action.async(parse.anyContent) {
    _ =>
      val received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
      val messageType = movementType match {
        case MovementType.Arrival   => MessageType.ArrivalNotification
        case MovementType.Departure => MessageType.DeclarationData
      }
      val message  = messageService.createEmptyMessage(messageType, received)
      val movement = movementFactory.createEmptyMovement(eori, movementType, message, received, received)

      (for {
        _ <- repo.insert(movement).asPresentation
      } yield MovementResponse(movement._id, Some(movement.messages.head.id))).fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        response => Ok(Json.toJson(response))
      )
  }

  def updateMovement(movementId: MovementId, triggerId: Option[MessageId] = None): Action[Source[ByteString, _]] =
    contentTypeRoute {
      case Some(_) => updateMovementWithStream(movementId, triggerId)
      case None    => updateMovementWithObjectStoreURI(movementId, triggerId)
    }

  // PATCH methods for updating a specific message

  // Initiated from SDES callback
  def updateMessageStatus(movementId: MovementId, messageId: MessageId): Action[JsValue] =
    Action.async(parse.json) {
      implicit request =>
        (for {
          updateStatus <- require[UpdateStatus](request.body)
          _ <- repo
            .updateMessage(movementId, messageId, UpdateMessageData(updateStatus), OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC))
            .asPresentation
        } yield Ok)
          .valueOr[Result](
            presentationError => Status(presentationError.code.statusCode)(Json.toJson(presentationError))
          )
    }

  // Initiated from trader/Upscan
  def updateMessage(eori: EORINumber, movementType: MovementType, movementId: MovementId, messageId: MessageId): Action[JsValue] = {
    def updateMessageMetadataOnly(status: MessageStatus, messageType: Option[MessageType], received: OffsetDateTime) =
      for {
        maybeMovementToUpdate <- repo.getMovementWithoutMessages(eori, movementId, movementType).asPresentation
        _                     <- ensureMovement(maybeMovementToUpdate, movementId)
        _ <- repo
          .updateMessage(movementId, messageId, UpdateMessageData(status = status, messageType = messageType), received)
          .asPresentation
      } yield Ok

    def verifyMessageType(messageType: MessageType, expectedType: Option[MessageType]): EitherT[Future, PresentationError, Unit] =
      expectedType match {
        // If we have already set a message type, we should verify that's what we have.
        case None                        => EitherT.rightT(())
        case Some(t) if t == messageType => EitherT.rightT(())
        case Some(_)                     => EitherT.leftT(PresentationError.badRequestError("Message type does not match"))
      }

    def updateMessageWithBody(uri: ObjectStoreURI, status: MessageStatus, messageType: MessageType, received: OffsetDateTime)(implicit
      hc: HeaderCarrier
    ) =
      for {
        maybeMovementToUpdate <- repo.getMovementWithoutMessages(eori, movementId, movementType).asPresentation
        movement              <- ensureMovement(maybeMovementToUpdate, movementId)
        message <- repo.getSingleMessage(eori, movementId, messageId, movementType).asPresentation.flatMap {
          case Some(x) => EitherT.rightT[Future, PresentationError](x)
          case None    => EitherT.leftT[Future, MessageResponse](PresentationError.notFoundError("Message does not exist"))
        }
        _ <- verifyMessageType(messageType, message.messageType)
        generatedDate <- updateMetadataIfRequired(
          movementId,
          message.messageType.getOrElse(messageType),
          uri,
          received,
          movement.movementEORINumber.isDefined
        )
        _ <- repo
          .updateMessage(movementId, messageId, UpdateMessageData(Some(uri), None, None, status, Some(messageType), Some(generatedDate)), received)
          .asPresentation
      } yield Ok

    Action.async(parse.json) {
      implicit request =>
        val received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
        (for {
          updateMessageMetadata <- require[UpdateMessageMetadata](request.body)
          _                     <- validateMessageType(updateMessageMetadata.messageType, movementType).asPresentation
        } yield updateMessageMetadata)
          .flatMap {
            case UpdateMessageMetadata(None, status, messageType) =>
              updateMessageMetadataOnly(status, messageType, received)
            case UpdateMessageMetadata(Some(uri), status, Some(messageType)) =>
              updateMessageWithBody(uri, status, messageType, received)
            case _ =>
              EitherT.leftT[Future, Status](PresentationError.badRequestError("A message type must be supplied when a body/uri is supplied"))
          }
          .valueOr[Result](
            presentationError => Status(presentationError.code.statusCode)(Json.toJson(presentationError))
          )
    }
  }

  @nowarn // deprecation
  private def updateMovementWithObjectStoreURI(movementId: MovementId, triggerId: Option[MessageId] = None): Action[AnyContent] =
    Action.async(parse.anyContent) {
      implicit request =>
        (for {
          messageType                 <- extract(request.headers).asPresentation
          objectStoreURI              <- extractObjectStoreURI(request.headers)
          objectStoreResourceLocation <- extractResourceLocation(objectStoreURI)
          sourceFile                  <- objectStoreService.getObjectStoreFile(objectStoreResourceLocation).asPresentation
          messageData                 <- messagesXmlParsingService.extractMessageData(sourceFile, messageType).asPresentation
          received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
          message = messageService
            .create(
              movementId,
              messageType,
              messageData.generationDate,
              received,
              triggerId,
              objectStoreURI,
              messageType.statusOnAttach
            )
          _ <- repo.updateMessages(movementId, message, messageData.mrn, received).asPresentation
        } yield message.id).fold[Result](
          baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
          id => Ok(Json.toJson(UpdateMovementResponse(id)))
        )
    }

  private def updateMovementWithStream(movementId: MovementId, triggerId: Option[MessageId]): Action[Source[ByteString, _]] =
    Action.streamWithSize {
      implicit request => size =>
        {
          implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
          for {
            messageType <- extract(request.headers).asPresentation
            messageData <- messagesXmlParsingService.extractMessageData(request.body, messageType).asPresentation
            received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
            message <- messageService
              .create(
                movementId,
                messageType,
                messageData.generationDate,
                received,
                triggerId,
                size,
                request.body,
                messageType.statusOnAttach
              )
              .asPresentation
            _ <- repo.updateMessages(movementId, message, messageData.mrn, received).asPresentation
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

  private def ensureMovement(movement: Option[MovementWithoutMessages], movementId: MovementId): EitherT[Future, PresentationError, MovementWithoutMessages] =
    EitherT(
      Future.successful(movement.map(Right.apply).getOrElse(Left(PresentationError.notFoundError(s"Movement with ID ${movementId.value} was not found"))))
    )

  // Large Messages: Only perform this step if we haven't updated the movement EORI and we have an Object Store reference
  private def updateMetadataIfRequired(
    movementId: MovementId,
    messageType: MessageType,
    objectStoreURI: ObjectStoreURI,
    received: OffsetDateTime,
    hasEoriAlready: Boolean
  )(implicit hc: HeaderCarrier): EitherT[Future, PresentationError, OffsetDateTime] = {

    def extractAndUpdate(source: Source[ByteString, _]): EitherT[Future, PresentationError, OffsetDateTime] =
      withReusableSource(source) {
        fileSource =>
          for {
            extractedData        <- movementsXmlParsingService.extractData(messageType, fileSource).asPresentation
            extractedMessageData <- messagesXmlParsingService.extractMessageData(fileSource, messageType).asPresentation
            _ <- repo
              .updateMovement(
                movementId,
                extractedData
                  .filterNot(
                    _ => hasEoriAlready
                  )
                  .map(_.movementEoriNumber),
                extractedData.flatMap(_.movementReferenceNumber),
                received
              )
              .asPresentation
          } yield extractedMessageData.generationDate
      }

    for {
      resourceLocation <- extractResourceLocation(objectStoreURI)
      source           <- objectStoreService.getObjectStoreFile(resourceLocation).asPresentation
      date             <- extractAndUpdate(source)
    } yield date
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

  private def validateMessageType(
    messageType: Option[MessageType],
    movementType: MovementType
  ): EitherT[Future, MessageTypeExtractError, Unit] =
    EitherT {
      (messageType, movementType) match {
        case (Some(messageType), MovementType.Arrival) =>
          MessageType.checkArrivalMessageType(messageType.code) match {
            case None    => Future.successful(Left(InvalidMessageType(s"Invalid messageType value: $messageType")))
            case Some(_) => Future.successful(Right((): Unit))
          }
        case (Some(messageType), MovementType.Departure) =>
          MessageType.checkDepartureMessageType(messageType.code) match {
            case None =>
              Future.successful(Left(InvalidMessageType(s"Invalid messageType value: $messageType")))
            case Some(_) =>
              Future.successful(Right((): Unit))
          }

        case (None, _) => Future.successful(Right((): Unit))
      }
    }

}
