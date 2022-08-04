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
import cats.data.NonEmptyList
import com.google.inject.ImplementedBy
import uk.gov.hmrc.transitmovements.models.responses.DeclarationResponse
import uk.gov.hmrc.transitmovements.models.values.ShortUUID
import uk.gov.hmrc.transitmovements.models.DeclarationData
import uk.gov.hmrc.transitmovements.models.Departure
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.repositories.DeparturesRepository
import uk.gov.hmrc.transitmovements.services.errors.MongoError
import uk.gov.hmrc.transitmovements.services.errors.StreamError

import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[DeparturesFactoryImpl])
trait DeparturesFactory {

  def create(
    eori: EORINumber,
    declarationData: DeclarationData,
    tempFile: Source[ByteString, Future[IOResult]]
  ): EitherT[Future, StreamError, Departure]
}

class DeparturesFactoryImpl @Inject() (
  clock: Clock,
  random: SecureRandom
)(implicit
  val materializer: Materializer
) extends DeparturesFactory {

  def create(
    eori: EORINumber,
    declarationData: DeclarationData,
    tempFile: Source[ByteString, Future[IOResult]]
  ): EitherT[Future, StreamError, Departure] =
    getMessageBody(tempFile).map {
      message =>
        Departure(
          _id = DepartureId(ShortUUID.next(clock, random)),
          enrollmentEORINumber = eori,
          movementEORINumber = declarationData.movementEoriNumber,
          movementReferenceNumber = None,
          created = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC),
          updated = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC),
          messages = NonEmptyList.one(createDeclarationMessage(declarationData, message))
        )
    }

  //To Consider: Refactor to a MessageFactory?
  private def createDeclarationMessage(declarationData: DeclarationData, messageBody: String): Message =
    Message(
      id = MessageId(ShortUUID.next(clock, random)),
      received = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC),
      generated = declarationData.generationDate,
      messageType = MessageType.DeclarationData,
      triggerId = None,
      url = None,
      body = Some(messageBody)
    )

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
