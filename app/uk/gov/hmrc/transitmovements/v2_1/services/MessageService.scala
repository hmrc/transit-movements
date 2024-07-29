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

package uk.gov.hmrc.transitmovements.v2_1.services

import cats.data.EitherT
import com.google.inject.ImplementedBy
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.transitmovements.models.requests.common.MessageId
import uk.gov.hmrc.transitmovements.models.requests.common.MovementId
import uk.gov.hmrc.transitmovements.v2_1.models._
import uk.gov.hmrc.transitmovements.v2_1.models.values.ShortUUID
import uk.gov.hmrc.transitmovements.v2_1.services.errors.ObjectStoreError
import uk.gov.hmrc.transitmovements.v2_1.services.errors.StreamError

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

  def createEmptyMessage(
    messageType: Option[MessageType],
    received: OffsetDateTime
  ): Message

  def storeIfLarge(movementId: MovementId, messageId: MessageId, size: Long, src: Source[ByteString, _])(implicit
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
    storeIfLarge(movementId, messageId, size, source).map {
      bodyStorage =>
        Message(
          id = messageId,
          received = received,
          generated = Some(generationDate),
          messageType = Some(messageType),
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

  def createEmptyMessage(
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
      size = None,
      status = Some(MessageStatus.Pending)
    )

  override def storeIfLarge(movementId: MovementId, messageId: MessageId, size: Long, src: Source[ByteString, _])(implicit
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
