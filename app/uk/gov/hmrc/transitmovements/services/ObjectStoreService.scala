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

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import com.google.inject.ImplementedBy
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.objectstore.client.play.Implicits._
import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.ObjectStoreResourceLocation
import uk.gov.hmrc.transitmovements.models.ObjectStoreURI

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject._
import scala.concurrent._
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

@ImplementedBy(classOf[ObjectStoreServiceImpl])
trait ObjectStoreService {

  def getObjectStoreFile(
    objectStoreResourceLocation: ObjectStoreResourceLocation
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): EitherT[Future, ObjectStoreError, Source[ByteString, _]]

  def addMessage(movementId: MovementId, messageId: MessageId, source: Source[ByteString, _])(implicit
    ec: ExecutionContext,
    hc: HeaderCarrier
  ): EitherT[Future, ObjectStoreError, ObjectSummaryWithMd5]

}

@Singleton
class ObjectStoreServiceImpl @Inject() (implicit materializer: Materializer, clock: Clock, client: PlayObjectStoreClient) extends ObjectStoreService {

  override def getObjectStoreFile(
    objectStoreResourceLocation: ObjectStoreResourceLocation
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): EitherT[Future, ObjectStoreError, Source[ByteString, _]] =
    EitherT(
      client
        .getObject[Source[ByteString, NotUsed]](
          Path.File(objectStoreResourceLocation.value),
          ObjectStoreURI.expectedOwner
        )
        .flatMap {
          case Some(source) => Future.successful(Right(source.content))
          case _            => Future.successful(Left(ObjectStoreError.FileNotFound(objectStoreResourceLocation.value)))
        }
        .recover {
          case NonFatal(ex) => Left(ObjectStoreError.UnexpectedError(Some(ex)))
        }
    )

  private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

  override def addMessage(
    movementId: MovementId,
    messageId: MessageId,
    source: Source[ByteString, _]
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): EitherT[Future, ObjectStoreError, ObjectSummaryWithMd5] =
    EitherT {
      val formattedDateTime = dateTimeFormatter.format(OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC))

      (for {
        response <- client.putObject(
          path = Path.Directory(s"movements/${movementId.value}").file(s"${movementId.value}-${messageId.value}-$formattedDateTime.xml"),
          content = source,
          owner = "common-transit-conversion-traders"
        )
      } yield response)
        .map {
          objectSummary =>
            Right(objectSummary)
        }
        .recover {
          case NonFatal(thr) =>
            Left(ObjectStoreError.UnexpectedError(Some(thr)))
        }
    }
}
