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

package uk.gov.hmrc.transitmovements.v2_1.controllers

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import cats.data.EitherT
import play.api.Logging
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
import uk.gov.hmrc.transitmovements.config.Constants.Predicates._
import uk.gov.hmrc.transitmovements.models.requests.common.EORINumber
import uk.gov.hmrc.transitmovements.models.requests.common.ItemCount
import uk.gov.hmrc.transitmovements.models.requests.common.LocalReferenceNumber
import uk.gov.hmrc.transitmovements.models.requests.common.MessageId
import uk.gov.hmrc.transitmovements.models.requests.common.MovementId
import uk.gov.hmrc.transitmovements.models.requests.common.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.requests.common.PageNumber
import uk.gov.hmrc.transitmovements.v2_1.controllers.actions.InternalAuthActionProvider
import uk.gov.hmrc.transitmovements.v2_1.controllers.errors.ConvertError
import uk.gov.hmrc.transitmovements.v2_1.controllers.errors.MessageTypeExtractError
import uk.gov.hmrc.transitmovements.v2_1.controllers.errors.MessageTypeExtractError.InvalidMessageType
import uk.gov.hmrc.transitmovements.v2_1.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovements.v2_1.models._
import uk.gov.hmrc.transitmovements.v2_1.models.requests.UpdateMessageMetadata
import uk.gov.hmrc.transitmovements.v2_1.models.requests.UpdateStatus
import uk.gov.hmrc.transitmovements.v2_1.models.responses.MovementResponse
import uk.gov.hmrc.transitmovements.v2_1.models.responses.UpdateMovementResponse
import uk.gov.hmrc.transitmovements.v2_1.services._
import uk.gov.hmrc.transitmovements.stream.StreamingParsers
import uk.gov.hmrc.transitmovements.utils.StreamWithFile

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
  repo: PersistenceService,
  movementsXmlParsingService: MovementsXmlParsingService,
  messagesXmlParsingService: MessagesXmlParsingService,
  objectStoreService: ObjectStoreService,
  internalAuth: InternalAuthActionProvider
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

  private def createArrival(eori: EORINumber): Action[Source[ByteString, _]] = internalAuth(WRITE_MOVEMENT).async(streamFromMemory) {
    implicit request =>
      {
        for {
          source      <- reUsableSourceRequest(request)
          arrivalData <- movementsXmlParsingService.extractArrivalData(source.lift(1).get).asPresentation
          received   = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
          movementId = movementFactory.generateId()
          size <- calculateSize(source.lift(2).get)
          message <- messageService
            .create(movementId, MessageType.ArrivalNotification, arrivalData.generationDate, received, None, size, source.lift(3).get, MessageStatus.Processing)
            .asPresentation
          movement = movementFactory.createArrival(movementId, eori, MovementType.Arrival, arrivalData, message, received, received)
          _ <- repo.insertMovement(movement).asPresentation
        } yield MovementResponse(movement._id, Some(movement.messages.head.id))
      }.fold[Result](baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)), response => Ok(Json.toJson(response)))
  }

  private def createDeparture(eori: EORINumber): Action[Source[ByteString, _]] = internalAuth(WRITE_MOVEMENT).streamWithSize {
    implicit request => size =>
      {
        implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
        for {
          source          <- reUsableSourceRequest(request)
          declarationData <- movementsXmlParsingService.extractDeclarationData(source.lift(1).get).asPresentation
          received   = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
          movementId = movementFactory.generateId()
          size <- calculateSize(source.lift(2).get)
          message <- messageService
            .create(movementId, MessageType.DeclarationData, declarationData.generationDate, received, None, size, source.lift(3).get, MessageStatus.Processing)
            .asPresentation
          movement = movementFactory.createDeparture(movementId, eori, MovementType.Departure, declarationData, message, received, received)
          _ <- repo.insertMovement(movement).asPresentation
        } yield MovementResponse(movement._id, Some(movement.messages.head.id))
      }.fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        response => Ok(Json.toJson(response))
      )
  }

  private def createEmptyMovement(eori: EORINumber, movementType: MovementType): Action[AnyContent] = internalAuth(WRITE_MOVEMENT).async(parse.anyContent) {
    _ =>
      val received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
      val messageType = movementType match {
        case MovementType.Arrival   => MessageType.ArrivalNotification
        case MovementType.Departure => MessageType.DeclarationData
      }

      val message  = messageService.createEmptyMessage(Some(messageType), received)
      val movement = movementFactory.createEmptyMovement(eori, movementType, message, received, received)

      (for {
        _ <- repo.insertMovement(movement).asPresentation
      } yield MovementResponse(movement._id, Some(movement.messages.head.id))).fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        response => Ok(Json.toJson(response))
      )
  }

  def updateMovement(movementId: MovementId, triggerId: Option[MessageId] = None): Action[Source[ByteString, _]] =
    contentTypeRoute {
      case Some(_) => updateMovementWithStream(movementId, triggerId)
      case None    => attachEmptyMessage(movementId)
    }

  // PATCH methods for updating a specific message

  // Initiated from SDES callback
  def updateMessageStatus(movementId: MovementId, messageId: MessageId): Action[JsValue] =
    internalAuth(WRITE_STATUS).async(parse.json) {
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
        _ <- repo.getMovementWithoutMessages(eori, movementId, movementType).asPresentation
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
        movement <- repo.getMovementWithoutMessages(eori, movementId, movementType).asPresentation
        message  <- repo.getSingleMessage(eori, movementId, messageId, movementType).asPresentation
        _        <- verifyMessageType(messageType, message.messageType)
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

    internalAuth(WRITE_MESSAGE).async(parse.json) {
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

  private def updateMovementWithStream(movementId: MovementId, triggerId: Option[MessageId]): Action[Source[ByteString, _]] =
    internalAuth(WRITE_MESSAGE).async(streamFromMemory) {
      implicit request =>
        {
          implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
          for {
            messageType <- extract(request.headers).asPresentation
            source      <- reUsableSourceRequest(request)
            messageData <- messagesXmlParsingService.extractMessageData(source.lift(1).get, messageType).asPresentation
            received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
            size <- calculateSize(source.lift(2).get)
            message <- messageService
              .create(
                movementId,
                messageType,
                messageData.generationDate,
                received,
                triggerId,
                size,
                source.lift(3).get,
                messageType.statusOnAttach
              )
              .asPresentation
            _ <- repo.attachMessage(movementId, message, messageData.mrn, received).asPresentation
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
    movementEORI: Option[EORINumber] = None,
    movementReferenceNumber: Option[MovementReferenceNumber] = None,
    page: Option[PageNumber] = None,
    count: Option[ItemCount] = None,
    receivedUntil: Option[OffsetDateTime] = None,
    localReferenceNumber: Option[LocalReferenceNumber] = None
  ): Action[AnyContent] = internalAuth(READ_MOVEMENT).async {
    repo
      .getMovements(eoriNumber, movementType, updatedSince, movementEORI, movementReferenceNumber, page, count, receivedUntil, localReferenceNumber)
      .asPresentation
      .fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        movements => Ok(Json.toJson(movements))
      )
  }

  def getMovementWithoutMessages(eoriNumber: EORINumber, movementType: MovementType, movementId: MovementId): Action[AnyContent] =
    internalAuth(READ_MOVEMENT).async {
      repo
        .getMovementWithoutMessages(eoriNumber, movementId, movementType)
        .asPresentation
        .fold[Result](baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)), arrivalWithoutMessages => Ok(Json.toJson(arrivalWithoutMessages)))
    }

  def getMessage(eoriNumber: EORINumber, movementType: MovementType, movementId: MovementId, messageId: MessageId): Action[AnyContent] =
    internalAuth(READ_MESSAGE).async {
      repo
        .getSingleMessage(eoriNumber, movementId, messageId, movementType)
        .asPresentation
        .fold[Result](
          baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
          message => Ok(Json.toJson(message))
        )
    }

  def getMessages(
    eoriNumber: EORINumber,
    movementType: MovementType,
    movementId: MovementId,
    receivedSince: Option[OffsetDateTime] = None,
    page: Option[PageNumber] = None,
    count: Option[ItemCount] = None,
    receivedUntil: Option[OffsetDateTime] = None
  ): Action[AnyContent] =
    internalAuth(READ_MESSAGE).async {
      (for {
        _        <- repo.getMovementWithoutMessages(eoriNumber, movementId, movementType).asPresentation
        messages <- repo.getMessages(eoriNumber, movementId, movementType, receivedSince, page, count, receivedUntil).asPresentation
      } yield messages).fold[Result](
        baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
        messages => Ok(Json.toJson(messages))
      )

    }

  // Large Messages: Only perform this step if we haven't updated the movement EORI and we have an Object Store reference
  private def updateMetadataIfRequired(
    movementId: MovementId,
    messageType: MessageType,
    objectStoreURI: ObjectStoreURI,
    received: OffsetDateTime,
    hasEoriAlready: Boolean
  )(implicit hc: HeaderCarrier): EitherT[Future, PresentationError, OffsetDateTime] = {

    def extractAndUpdate(source: Source[ByteString, _]): EitherT[Future, PresentationError, OffsetDateTime] =
      for {
        source               <- reUsableSource(source)
        extractedData        <- movementsXmlParsingService.extractData(messageType, source.lift(1).get).asPresentation
        extractedMessageData <- messagesXmlParsingService.extractMessageData(source.lift(2).get, messageType).asPresentation
        _ <- repo
          .updateMovement(
            movementId,
            extractedData
              .filterNot(
                _ => hasEoriAlready
              )
              .flatMap(_.movementEoriNumber),
            extractedData.flatMap(_.movementReferenceNumber),
            extractedData.flatMap(_.localReferenceNumber),
            extractedData.flatMap(_.messageSender),
            received
          )
          .asPresentation
      } yield extractedMessageData.generationDate

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

  private def attachEmptyMessage(movementId: MovementId): Action[AnyContent] = internalAuth(WRITE_MESSAGE).async {
    val received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)

    val message = messageService.createEmptyMessage(
      None,
      received
    )
    (for {
      _ <- repo.attachMessage(movementId, message, None, received).asPresentation
    } yield message).fold[Result](
      baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
      message => Ok(Json.toJson(UpdateMovementResponse(message.id)))
    )
  }

  private def materializeSource(source: Source[ByteString, _]): EitherT[Future, PresentationError, Seq[ByteString]] =
    EitherT(
      source
        .runWith(Sink.seq)
        .map(Right(_): Either[PresentationError, Seq[ByteString]])
        .recover {
          error =>
            Left(PresentationError.internalServiceError(cause = Some(error)))
        }
    )

  // Function to create a new source from the materialized sequence
  private def createReusableSource(seq: Seq[ByteString]): Source[ByteString, _] = Source(seq.toList)

  private def reUsableSourceRequest(request: Request[Source[ByteString, _]]): EitherT[Future, PresentationError, List[Source[ByteString, _]]] = for {
    byteStringSeq <- materializeSource(request.body)
  } yield List.fill(4)(createReusableSource(byteStringSeq))

  private def reUsableSource(source: Source[ByteString, _]): EitherT[Future, PresentationError, List[Source[ByteString, _]]] = for {
    byteStringSeq <- materializeSource(source)
  } yield List.fill(4)(createReusableSource(byteStringSeq))

  // Function to calculate the size using EitherT
  private def calculateSize(source: Source[ByteString, _]): EitherT[Future, PresentationError, Long] = {
    val sizeFuture: Future[Either[PresentationError, Long]] = source
      .map(_.size.toLong)
      .runWith(Sink.fold(0L)(_ + _))
      .map(
        size => Right(size): Either[PresentationError, Long]
      )
      .recover {
        case _: Exception => Left(PresentationError.internalServiceError())
      }

    EitherT(sizeFuture)
  }

}
