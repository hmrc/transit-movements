/*
 * Copyright 2021 HM Revenue & Customs
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

package services

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import cats.syntax.all._
import com.google.inject.ImplementedBy
import config.AppConfig
import connectors.EisRouterConnector
import models.DepartureMessageType
import models.DepartureRequestMessageType
import models.DepartureResponseMessageType
import models.MessageType
import models.errors.BadRequestError
import models.errors.InternalServiceError
import models.errors.TransitMovementError
import models.errors.UpstreamServiceError
import models.errors.XmlValidationError
import models.request.DeclarationDataRequest
import models.values.DepartureId
import models.values.EoriNumber
import models.values.MessageId
import org.mongodb.scala._
import play.api.Logging
import play.api.http.ContentTypes
import repositories.DeparturesRepository
import repositories.MessagesRepository
import repositories.Transactions
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.transaction.TransactionConfiguration
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.play.Implicits._
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClientEither

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[DeparturesServiceImpl])
trait DeparturesService {
  def sendDeclarationData(
    eoriNumber: EoriNumber,
    request: Source[ByteString, _]
  )(implicit hc: HeaderCarrier): Future[Either[TransitMovementError, DepartureId]]

  def receiveMessage(
    departureId: DepartureId,
    messageType: DepartureMessageType,
    request: Source[ByteString, _]
  )(implicit hc: HeaderCarrier): Future[Either[TransitMovementError, MessageId]]
}

@Singleton
class DeparturesServiceImpl @Inject() (
  departureRepo: DeparturesRepository,
  messageRepo: MessagesRepository,
  objectStore: PlayObjectStoreClientEither,
  connector: EisRouterConnector,
  validator: XmlValidationService,
  parser: DeparturesXmlParsingService,
  appConfig: AppConfig,
  clock: Clock,
  val mongoComponent: MongoComponent
)(implicit val system: ActorSystem)
  extends DeparturesService
  with Logging
  with Transactions {

  private implicit val executionContext: ExecutionContext =
    system.dispatcher
  private implicit val transactionConfig: TransactionConfiguration =
    TransactionConfiguration.strict
  private val dateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

  private def schemaValidate(
    request: Source[ByteString, _]
  ): EitherT[Future, TransitMovementError, Unit] =
    EitherT {
      validator.validate(MessageType.DeclarationData, request)
    }.leftMap { errors =>
      XmlValidationError(MessageType.DeclarationData, errors)
    }

  private def parseDeclarationData(
    eoriNumber: EoriNumber,
    request: Source[ByteString, _]
  ): EitherT[Future, BadRequestError, DeclarationDataRequest] =
    EitherT {
      parser.parseDeclarationDataRequest(eoriNumber, request)
    }

  private def insertDepartureMovement(
    session: ClientSession,
    request: DeclarationDataRequest
  ): EitherT[Future, TransitMovementError, DepartureId] =
    EitherT.right[TransitMovementError] {
      departureRepo.insertDeparture(session, request)
    }

  private def insertDepartureMessage(
    session: ClientSession,
    departureId: DepartureId,
    request: DeclarationDataRequest,
    messagePath: Path.File
  ): EitherT[Future, TransitMovementError, MessageId] =
    EitherT.right[TransitMovementError] {
      messageRepo.insertMessage(session, departureId, request, messagePath)
    }

  private def makeObjectStorePath(
    departureId: DepartureId
  ): Path.File = {
    val dateTime        = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
    val objectStoreName = s"${departureId.hexString}-${dateTimeFormatter.format(dateTime)}.xml"
    appConfig.objectStoreDirectory.file(objectStoreName)
  }

  private def putObjectStoreEntry(
    path: Path.File,
    data: Source[ByteString, _]
  )(implicit hc: HeaderCarrier): EitherT[Future, TransitMovementError, Unit] =
    EitherT(
      objectStore.putObject(path, data, contentType = Some(ContentTypes.XML))
    ).leftMap {
      case err: UpstreamErrorResponse =>
        UpstreamServiceError.causedBy(err)
      case NonFatal(err) =>
        InternalServiceError.causedBy(err)
    }

  private def sendEisMessage(
    messageType: DepartureRequestMessageType,
    data: Source[ByteString, _]
  )(implicit hc: HeaderCarrier): EitherT[Future, TransitMovementError, Unit] =
    EitherT {
      connector.sendMessage(messageType, data)
    }.leftMap { error =>
      UpstreamServiceError.causedBy(error)
    }

  override def sendDeclarationData(
    eoriNumber: EoriNumber,
    request: Source[ByteString, _]
  )(implicit hc: HeaderCarrier): Future[Either[TransitMovementError, DepartureId]] =
    withTransaction { session =>
      val sendDeclaration = for {

        _ <- schemaValidate(request)

        declarationData <- parseDeclarationData(eoriNumber, request)

        departureId <- insertDepartureMovement(session, declarationData)

        objectStorePath = makeObjectStorePath(departureId)

        // TODO: What happens if sending the message succeeds and something else fails?
        // The transaction will be rolled back but the movement will be with NCTS.
        // Keep the message sent status logic?
        _ <- (
          sendEisMessage(MessageType.DeclarationData, request),
          putObjectStoreEntry(objectStorePath, request),
          insertDepartureMessage(session, departureId, declarationData, objectStorePath)
        ).tupled

      } yield departureId

      sendDeclaration.value.flatTap {
        case Right(_) =>
          Future.unit
        case Left(_) =>
          if (session.hasActiveTransaction())
            session.abortTransaction().toSingle.toFuture
          else
            Future.unit
      }
    }

  override def receiveMessage(
    departureId: DepartureId,
    messageType: DepartureMessageType,
    request: Source[ByteString, _]
  )(implicit hc: HeaderCarrier): Future[Either[TransitMovementError, MessageId]] =
    withTransaction { session =>
      messageType match {
        case requestMessage: DepartureRequestMessageType =>
          // User message logic
          ???
        case responseMessage: DepartureResponseMessageType =>
          // NCTS message logic
          ???
        case MessageType.XmlNack =>
          // NCTS error logic
          ???
      }
    }
}
