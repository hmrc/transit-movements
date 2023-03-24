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

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import com.google.inject.ImplementedBy
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageStatus
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.ObjectStoreURI
import uk.gov.hmrc.transitmovements.models.mongo.UpdateMessageModel
import uk.gov.hmrc.transitmovements.models.requests.UpdateMessageMetadata
import uk.gov.hmrc.transitmovements.models.values.ShortUUID
import uk.gov.hmrc.transitmovements.services.errors.MessageError
import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError

import java.net.URI
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[MessageServiceImpl])
trait MessageService {

  def generateId(): MessageId

  def create(
    movementId: MovementId,
    messageType: MessageType,
    generationDate: OffsetDateTime,
    received: OffsetDateTime,
    triggerId: Option[MessageId],
    body: Source[ByteString, _],
    size: Long,
    status: MessageStatus
  )(implicit hc: HeaderCarrier): EitherT[Future, MessageError, Message]

  def createWithURI(
    messageType: MessageType,
    generationDate: OffsetDateTime,
    received: OffsetDateTime,
    triggerId: Option[MessageId],
    uri: ObjectStoreURI,
    status: MessageStatus
  ): Message

  def createEmpty(
    messageType: Option[MessageType],
    received: OffsetDateTime
  ): Message

  def update(
    movementId: MovementId,
    messageId: MessageId,
    body: Source[ByteString, _],
    size: Long,
    status: MessageStatus
  )(implicit hc: HeaderCarrier): EitherT[Future, MessageError, UpdateMessageModel]

}

class MessageServiceImpl @Inject() (
  clock: Clock,
  random: SecureRandom,
  objectStoreService: ObjectStoreService,
  smallMessageLimitService: SmallMessageLimitService
)(implicit
  val materializer: Materializer,
  ec: ExecutionContext
) extends MessageService {

  def generateId(): MessageId = MessageId(ShortUUID.next(clock, random))

  def create(
    movementId: MovementId,
    messageType: MessageType,
    generationDate: OffsetDateTime,
    received: OffsetDateTime,
    triggerId: Option[MessageId],
    body: Source[ByteString, _],
    size: Long,
    status: MessageStatus
  )(implicit hc: HeaderCarrier): EitherT[Future, MessageError, Message] = {
    val message = Message(
      id = generateId(),
      received = received,
      generated = Some(generationDate),
      messageType = Some(messageType),
      triggerId = triggerId,
      uri = None,
      body = None,
      status = Some(status)
    )

    if (smallMessageLimitService.isLarge(size))
      sendToObjectStore(movementId, message.id, body).map(
        x => message.copy(uri = Some(new URI(x.value)))
      )
    else
      getMessageBody(body).map(
        b => message.copy(body = Some(b))
      )
  }

  def createWithURI(
    messageType: MessageType,
    generationDate: OffsetDateTime,
    received: OffsetDateTime,
    triggerId: Option[MessageId],
    uri: ObjectStoreURI,
    status: MessageStatus
  ): Message =
    Message(
      id = generateId(),
      received = received,
      generated = Some(generationDate),
      messageType = Some(messageType),
      triggerId = triggerId,
      uri = Some(new URI(uri.value)),
      body = None,
      status = Some(status)
    )

  def createEmpty(
    messageType: Option[MessageType],
    received: OffsetDateTime
  ): Message =
    Message(
      id = generateId(),
      received = received,
      generated = None,
      messageType = messageType,
      triggerId = None,
      uri = None,
      body = None,
      status = Some(MessageStatus.Pending)
    )

  def update(
    movementId: MovementId,
    messageId: MessageId,
    body: Source[ByteString, _],
    size: Long,
    status: MessageStatus
  )(implicit hc: HeaderCarrier): EitherT[Future, MessageError, UpdateMessageModel] =
    if (smallMessageLimitService.isLarge(size))
      sendToObjectStore(movementId, messageId, body).map(
        x => UpdateMessageModel(Some(x), None, status)
      )
    else
      getMessageBody(body).map(
        b => UpdateMessageModel(None, Some(b), status)
      )

  private def sendToObjectStore(movementId: MovementId, messageId: MessageId, source: Source[ByteString, _])(implicit
    hc: HeaderCarrier
  ): EitherT[Future, MessageError, ObjectStoreURI] =
    objectStoreService
      .putObjectStoreFile(movementId, messageId, source)
      .leftMap[MessageError] {
        case ObjectStoreError.UnexpectedError(thr) => MessageError.UnexpectedError(thr)
        case _                                     => MessageError.UnexpectedError(None)
      }
      .map {
        objectStoreSummary =>
          ObjectStoreURI(objectStoreSummary.location.asUri)
      }

  private def getMessageBody(tempFile: Source[ByteString, _]): EitherT[Future, MessageError, String] =
    EitherT {
      tempFile
        .fold("")(
          (curStr, newStr) => curStr + newStr.utf8String
        )
        .runWith(Sink.head[String])
        .map(Right[MessageError, String])
        .recover {
          case NonFatal(ex) => Left[MessageError, String](MessageError.UnexpectedError(Some(ex)))
        }
    }

}
