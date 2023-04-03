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
import uk.gov.hmrc.transitmovements.models.BodyStorage
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageStatus
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.ObjectStoreURI
import uk.gov.hmrc.transitmovements.models.values.ShortUUID
import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError
import uk.gov.hmrc.transitmovements.services.errors.StreamError

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
    size: Long,
    source: Source[ByteString, _],
    status: MessageStatus
  )(implicit hc: HeaderCarrier): EitherT[Future, StreamError, Message]

  @deprecated(message = "This should be phased out in favour of #create -> EitherT", since = "now")
  def create(
    movementId: MovementId,
    messageType: MessageType,
    generationDate: OffsetDateTime,
    received: OffsetDateTime,
    triggerId: Option[MessageId],
    objectStoreURI: ObjectStoreURI,
    status: MessageStatus
  ): Message

  def createEmptyMessage(
    messageType: MessageType,
    received: OffsetDateTime
  ): Message

  def storeIfAppropriate(movementId: MovementId, messageId: MessageId, size: Long, src: Source[ByteString, _])(implicit
    hc: HeaderCarrier
  ): EitherT[Future, StreamError, BodyStorage]

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

  override def generateId(): MessageId = MessageId(ShortUUID.next(clock, random))

  override def create(
    movementId: MovementId,
    messageType: MessageType,
    generationDate: OffsetDateTime,
    received: OffsetDateTime,
    triggerId: Option[MessageId],
    size: Long,
    source: Source[ByteString, _],
    status: MessageStatus
  )(implicit hc: HeaderCarrier): EitherT[Future, StreamError, Message] = {
    val messageId = generateId()
    storeIfAppropriate(movementId, messageId, size, source).map {
      bodyStorage =>
        Message(
          id = messageId,
          received = received,
          generated = Some(generationDate),
          messageType = messageType,
          triggerId = triggerId,
          uri = bodyStorage.objectStore.map(
            x => new URI(x.value)
          ),
          body = bodyStorage.mongo,
          size = Some(size),
          status = Some(status)
        )
    }
  }

  override def create(
    movementId: MovementId,
    messageType: MessageType,
    generationDate: OffsetDateTime,
    received: OffsetDateTime,
    triggerId: Option[MessageId],
    objectStoreURI: ObjectStoreURI,
    status: MessageStatus
  ): Message =
    Message(
      id = generateId(),
      received = received,
      generated = Some(generationDate),
      messageType = messageType,
      triggerId = triggerId,
      uri = Some(new URI(objectStoreURI.value)),
      body = None,
      size = None,
      status = Some(status)
    )

  override def createEmptyMessage(messageType: MessageType, received: OffsetDateTime): Message =
    Message(
      id = generateId(),
      received = received,
      generated = None,
      messageType = messageType,
      triggerId = None,
      uri = None,
      body = None,
      size = None,
      status = Some(MessageStatus.Pending)
    )

  override def storeIfAppropriate(movementId: MovementId, messageId: MessageId, size: Long, src: Source[ByteString, _])(implicit
    hc: HeaderCarrier
  ): EitherT[Future, StreamError, BodyStorage] =
    if (smallMessageLimitService.isLarge(size)) createObjectStoreObject(movementId, messageId, src).map(BodyStorage.objectStore)
    else getMessageBody(src).map(BodyStorage.mongo)

  private def createObjectStoreObject(movementId: MovementId, messageId: MessageId, src: Source[ByteString, _])(implicit
    hc: HeaderCarrier
  ): EitherT[Future, StreamError, ObjectStoreURI] =
    objectStoreService
      .putObjectStoreFile(movementId, messageId, src)
      .leftMap[StreamError] {
        case ObjectStoreError.UnexpectedError(caughtException) => StreamError.UnexpectedError(caughtException)
        case ObjectStoreError.FileNotFound(_)                  => StreamError.UnexpectedError(None)
      }
      .map(
        summary => ObjectStoreURI(summary.location.asUri)
      )

  private def getMessageBody(src: Source[ByteString, _]): EitherT[Future, StreamError, String] =
    EitherT {
      src
        .fold("")(
          (curStr, newStr) => curStr + newStr.utf8String
        )
        .runWith(Sink.head[String])
        .map(Right[StreamError, String])
        .recover {
          case NonFatal(ex) => Left[StreamError, String](StreamError.UnexpectedError(Some(ex)))
        }
    }

}
