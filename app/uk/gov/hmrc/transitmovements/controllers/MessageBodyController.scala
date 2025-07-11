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

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import cats.data.EitherT
import play.api.Logging
import play.api.http.MimeTypes
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.transitmovements.config.Constants.Predicates.READ_MESSAGE
import uk.gov.hmrc.transitmovements.config.Constants.Predicates.WRITE_MESSAGE
import uk.gov.hmrc.transitmovements.controllers.actions.InternalAuthActionProvider
import uk.gov.hmrc.transitmovements.controllers.errors.ConvertError
import uk.gov.hmrc.transitmovements.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.ExtractedData
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MessageData
import uk.gov.hmrc.transitmovements.models.MessageStatus
import uk.gov.hmrc.transitmovements.models.MovementType
import uk.gov.hmrc.transitmovements.models.ObjectStoreURI
import uk.gov.hmrc.transitmovements.models.UpdateMessageData
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse
import uk.gov.hmrc.transitmovements.services.MessageService
import uk.gov.hmrc.transitmovements.services.MessagesXmlParsingService
import uk.gov.hmrc.transitmovements.services.MovementsXmlParsingService
import uk.gov.hmrc.transitmovements.services.ObjectStoreService
import uk.gov.hmrc.transitmovements.services.PersistenceService
import uk.gov.hmrc.transitmovements.services.SourceManagementService
import uk.gov.hmrc.transitmovements.stream.StreamingParsers

import java.time.Clock
import java.time.OffsetDateTime
import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class MessageBodyController @Inject() (
  cc: ControllerComponents,
  persistenceService: PersistenceService,
  objectStoreService: ObjectStoreService,
  messagesXmlParsingService: MessagesXmlParsingService,
  movementsXmlParsingService: MovementsXmlParsingService,
  sourceManagementService: SourceManagementService,
  messageService: MessageService,
  internalAuth: InternalAuthActionProvider,
  clock: Clock
)(implicit
  ec: ExecutionContext,
  val materializer: Materializer
) extends BackendController(cc)
    with Logging
    with StreamingParsers
    with ConvertError
    with MessageTypeHeaderExtractor
    with ObjectStoreURIHelpers {

  def getBody(eori: EORINumber, movementType: MovementType, movementId: MovementId, messageId: MessageId): Action[AnyContent] =
    internalAuth(READ_MESSAGE).async {
      implicit request =>
        (for {
          message <- persistenceService.getSingleMessage(eori, movementId, messageId, movementType).asPresentation
          stream  <- body(message, messageId, movementId)
        } yield Ok.chunked(stream, Some(MimeTypes.XML)))
          .valueOrF(
            error => Future.successful(Status(error.code.statusCode)(Json.toJson(error)))
          )
    }

  def createBody(eori: EORINumber, movementType: MovementType, movementId: MovementId, messageId: MessageId): Action[Source[ByteString, ?]] =
    internalAuth(WRITE_MESSAGE).async(streamFromMemory) {
      implicit request =>
        val received = OffsetDateTime.now(clock)
        (for {
          message       <- persistenceService.getSingleMessage(eori, movementId, messageId, movementType).asPresentation
          _             <- ensureNoMessageBody(message)
          messageType   <- extract(request.headers).asPresentation
          source        <- sourceManagementService.replicateRequestSource(request, 4)
          messageData   <- messagesXmlParsingService.extractMessageData(source.head, messageType).asPresentation
          extractedData <- movementsXmlParsingService.extractData(messageType, source(1)).asPresentation
          size          <- sourceManagementService.calculateSize(source(2))
          bodyStorage   <- messageService.storeIfLarge(movementId, messageId, size, source(3)).asPresentation
          _ <- persistenceService
            .updateMessage(
              movementId,
              messageId,
              UpdateMessageData(
                bodyStorage.objectStore,
                bodyStorage.mongo,
                Some(size),
                messageType.statusOnAttach,
                Some(messageType),
                Some(messageData.generationDate)
              ),
              received
            )
            .asPresentation
          _ <- updateMovementMetadata(movementId, extractedData, messageData, received)
        } yield Created)
          .valueOrF(
            error => Future.successful(Status(error.code.statusCode)(Json.toJson(error)))
          )
    }

  private def ensureNoMessageBody(messageResponse: MessageResponse): EitherT[Future, PresentationError, Unit] =
    if (messageResponse.status.contains(MessageStatus.Pending)) EitherT.rightT((): Unit)
    else EitherT.leftT(PresentationError.conflictError(s"Body for ${messageResponse.id.value} already exists"))

  private def body(messageResponse: MessageResponse, messageId: MessageId, movementId: MovementId)(implicit
    hc: HeaderCarrier
  ): EitherT[Future, PresentationError, Source[ByteString, ?]] =
    messageResponse match {
      case MessageResponse(_, _, _, Some(body), _, _, _) => EitherT.rightT(Source.single(ByteString(body)))
      case MessageResponse(_, _, _, None, _, _, Some(uri)) =>
        for {
          resourceLocation <- extractResourceLocation(ObjectStoreURI(uri.toString))
          source <- objectStoreService
            .getObjectStoreFile(resourceLocation)
            .asPresentation(objectStoreErrorWithInternalServiceErrorConverter, implicitly[ExecutionContext])
        } yield source
      case _ => notFound(messageId, movementId)
    }

  private def notFound[A](messageId: MessageId, movementId: MovementId): EitherT[Future, PresentationError, A] =
    EitherT.leftT(PresentationError.notFoundError(s"Body of message ID ${messageId.value} for movement ID ${movementId.value} was not found"))

  private def updateMovementMetadata(
    movementId: MovementId,
    extractedData: Option[ExtractedData],
    messageData: MessageData,
    received: OffsetDateTime
  ): EitherT[Future, PresentationError, Unit] =
    persistenceService
      .updateMovement(
        movementId,
        extractedData.flatMap(_.movementEoriNumber),
        extractedData.flatMap(_.movementReferenceNumber).orElse(messageData.mrn),
        extractedData.flatMap(_.localReferenceNumber),
        extractedData.flatMap(_.messageSender),
        received
      )
      .asPresentation
}
