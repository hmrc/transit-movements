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

package uk.gov.hmrc.transitmovements.services

import cats.data.EitherT
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import uk.gov.hmrc.transitmovements.models.*
import uk.gov.hmrc.transitmovements.models.mongo.write.MongoMessage
import uk.gov.hmrc.transitmovements.models.mongo.write.MongoMessageUpdateData
import uk.gov.hmrc.transitmovements.models.mongo.write.MongoMovement
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse
import uk.gov.hmrc.transitmovements.repositories.MovementsRepository
import uk.gov.hmrc.transitmovements.services.errors.MongoError

import java.time.OffsetDateTime
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[PersistenceServiceImpl])
trait PersistenceService {

  def insertMovement(movement: Movement): EitherT[Future, MongoError, Unit]

  def updateMovement(
    movementId: MovementId,
    movementEORI: Option[EORINumber],
    mrn: Option[MovementReferenceNumber],
    lrn: Option[LocalReferenceNumber],
    messageSender: Option[MessageSender],
    received: OffsetDateTime
  ): EitherT[Future, MongoError, Unit]

  def attachMessage(
    movementId: MovementId,
    message: Message,
    mrn: Option[MovementReferenceNumber],
    received: OffsetDateTime
  ): EitherT[Future, MongoError, Unit]

  def updateMessage(
    movementId: MovementId,
    messageId: MessageId,
    message: UpdateMessageData,
    received: OffsetDateTime
  ): EitherT[Future, MongoError, Unit]

  // ---

  def getMovementWithoutMessages(
    eoriNumber: EORINumber,
    movementId: MovementId,
    movementType: MovementType
  ): EitherT[Future, MongoError, MovementWithoutMessages]

  def getMovementEori(
    movementId: MovementId
  ): EitherT[Future, MongoError, MovementWithEori]

  def getSingleMessage(
    eoriNumber: EORINumber,
    movementId: MovementId,
    messageId: MessageId,
    movementType: MovementType
  ): EitherT[Future, MongoError, MessageResponse]

  def getMessages(
    eoriNumber: EORINumber,
    movementId: MovementId,
    movementType: MovementType,
    received: Option[OffsetDateTime],
    page: Option[PageNumber] = None,
    count: Option[ItemCount] = None,
    receivedUntil: Option[OffsetDateTime] = None
  ): EitherT[Future, MongoError, PaginationMessageSummary]

  def getMessageIdsAndType(
    movementId: MovementId
  ): EitherT[Future, MongoError, Vector[MessageResponse]]

  def getMovements(
    eoriNumber: EORINumber,
    movementType: MovementType,
    updatedSince: Option[OffsetDateTime],
    movementEORI: Option[EORINumber],
    movementReferenceNumber: Option[MovementReferenceNumber],
    page: Option[PageNumber] = None,
    count: Option[ItemCount] = None,
    receivedUntil: Option[OffsetDateTime] = None,
    localReferenceNumber: Option[LocalReferenceNumber]
  ): EitherT[Future, MongoError, PaginationMovementSummary]

}

class PersistenceServiceImpl @Inject() (movementsRespository: MovementsRepository)(implicit ec: ExecutionContext) extends PersistenceService {

  override def insertMovement(movement: Movement): EitherT[Future, MongoError, Unit] =
    movementsRespository.insert(MongoMovement.from(movement))

  override def updateMovement(
    movementId: MovementId,
    movementEORI: Option[EORINumber],
    mrn: Option[MovementReferenceNumber],
    lrn: Option[LocalReferenceNumber],
    messageSender: Option[MessageSender],
    received: OffsetDateTime
  ): EitherT[Future, MongoError, Unit] =
    movementsRespository.updateMovement(movementId, movementEORI, mrn, lrn, messageSender, received)

  override def attachMessage(
    movementId: MovementId,
    message: Message,
    mrn: Option[MovementReferenceNumber],
    received: OffsetDateTime
  ): EitherT[Future, MongoError, Unit] =
    movementsRespository.attachMessage(movementId, MongoMessage.from(message), mrn, received)

  override def updateMessage(
    movementId: MovementId,
    messageId: MessageId,
    message: UpdateMessageData,
    received: OffsetDateTime
  ): EitherT[Future, MongoError, Unit] =
    movementsRespository.updateMessage(movementId, messageId, MongoMessageUpdateData.from(message), received)

  override def getMovementWithoutMessages(
    eoriNumber: EORINumber,
    movementId: MovementId,
    movementType: MovementType
  ): EitherT[Future, MongoError, MovementWithoutMessages] =
    movementsRespository.getMovementWithoutMessages(eoriNumber, movementId, movementType).map(_.asMovementWithoutMessages)

  override def getMovementEori(
    movementId: MovementId
  ): EitherT[Future, MongoError, MovementWithEori] =
    movementsRespository.getMovementEori(movementId).map(_.movementWithEori)

  override def getSingleMessage(
    eoriNumber: EORINumber,
    movementId: MovementId,
    messageId: MessageId,
    movementType: MovementType
  ): EitherT[Future, MongoError, MessageResponse] =
    movementsRespository.getSingleMessage(eoriNumber, movementId, messageId, movementType).map(_.asMessageResponse)

  override def getMessages(
    eoriNumber: EORINumber,
    movementId: MovementId,
    movementType: MovementType,
    received: Option[OffsetDateTime],
    page: Option[PageNumber],
    count: Option[ItemCount],
    receivedUntil: Option[OffsetDateTime]
  ): EitherT[Future, MongoError, PaginationMessageSummary] =
    movementsRespository.getMessages(eoriNumber, movementId, movementType, received, page, count, receivedUntil).map {
      messages =>
        PaginationMessageSummary(
          messages.totalCount,
          messages.messageSummary.map(_.asMessageResponse)
        )
    }

  override def getMessageIdsAndType(
    movementId: MovementId
  ): EitherT[Future, MongoError, Vector[MessageResponse]] =
    movementsRespository.getMessageIdsAndType(movementId).map {
      messages =>
        messages.map {
          message => message.asMessageResponse
        }
    }

  override def getMovements(
    eoriNumber: EORINumber,
    movementType: MovementType,
    updatedSince: Option[OffsetDateTime],
    movementEORI: Option[EORINumber],
    movementReferenceNumber: Option[MovementReferenceNumber],
    page: Option[PageNumber],
    count: Option[ItemCount],
    receivedUntil: Option[OffsetDateTime],
    localReferenceNumber: Option[LocalReferenceNumber]
  ): EitherT[Future, MongoError, PaginationMovementSummary] =
    movementsRespository
      .getMovements(eoriNumber, movementType, updatedSince, movementEORI, movementReferenceNumber, page, count, receivedUntil, localReferenceNumber)
      .map {
        movements =>
          PaginationMovementSummary(
            movements.totalCount,
            movements.movementSummary.map(_.asMovementWithoutMessages)
          )
      }
}
