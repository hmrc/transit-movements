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

package uk.gov.hmrc.transitmovements.services

import akka.stream.IOResult
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import com.google.inject.ImplementedBy

import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.values.ShortUUID
import uk.gov.hmrc.transitmovements.services.errors.StreamError

import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import javax.inject.Inject
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[MessageFactoryImpl])
trait MessageFactory {

  def create(
    messageType: MessageType,
    generationDate: OffsetDateTime,
    received: OffsetDateTime,
    triggerId: Option[MessageId],
    tempFile: Source[ByteString, Future[IOResult]]
  ): EitherT[Future, StreamError, Message]
}

class MessageFactoryImpl @Inject() (
  clock: Clock,
  random: SecureRandom
)(implicit
  val materializer: Materializer
) extends MessageFactory {

  def create(
    messageType: MessageType,
    generationDate: OffsetDateTime,
    received: OffsetDateTime,
    triggerId: Option[MessageId],
    tempFile: Source[ByteString, Future[IOResult]]
  ): EitherT[Future, StreamError, Message] =
    getMessageBody(tempFile).map {
      message =>
        Message(
          id = MessageId(ShortUUID.next(clock, random)),
          received = received,
          generated = generationDate,
          messageType = messageType,
          triggerId = triggerId,
          url = None,
          body = Some(message)
        )
    }

  private def getMessageBody(tempFile: Source[ByteString, Future[IOResult]]): EitherT[Future, StreamError, String] =
    EitherT {
      tempFile
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
