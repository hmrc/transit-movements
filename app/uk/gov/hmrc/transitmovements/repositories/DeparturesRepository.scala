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

package uk.gov.hmrc.transitmovements.repositories

import akka.pattern.retry
import cats.data.EitherT
import cats.data.NonEmptyList
import com.google.inject.ImplementedBy
import com.mongodb.client.model.Filters.{and => mAnd}
import com.mongodb.client.model.Filters.{eq => mEq}
import com.mongodb.client.model.Filters.{gte => mGte}
import com.mongodb.client.model.Updates.{push => mPush}
import com.mongodb.client.model.Updates.{set => mSet}
import com.mongodb.client.model.Updates.{combine => mCombine}
import org.bson.conversions.Bson
import org.mongodb.scala.model._
import org.mongodb.scala.model.Sorts.descending
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json._
import uk.gov.hmrc.transitmovements.config.AppConfig
import uk.gov.hmrc.transitmovements.models.Departure
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.DepartureWithoutMessages
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.formats.CommonFormats
import uk.gov.hmrc.transitmovements.models.formats.MongoFormats
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse
import uk.gov.hmrc.transitmovements.repositories.DeparturesRepositoryImpl.EPOCH_TIME
import uk.gov.hmrc.transitmovements.services.errors.MongoError
import uk.gov.hmrc.transitmovements.services.errors.MongoError._

import java.time.Clock
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent._
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

@ImplementedBy(classOf[DeparturesRepositoryImpl])
trait DeparturesRepository {
  def insert(departure: Departure): EitherT[Future, MongoError, Unit]
  def getDepartureWithoutMessages(eoriNumber: EORINumber, departureId: DepartureId): EitherT[Future, MongoError, Option[DepartureWithoutMessages]]
  def getSingleMessage(eoriNumber: EORINumber, departureId: DepartureId, messageId: MessageId): EitherT[Future, MongoError, Option[MessageResponse]]

  def getMessages(
    eoriNumber: EORINumber,
    departureId: DepartureId,
    received: Option[OffsetDateTime]
  ): EitherT[Future, MongoError, Option[NonEmptyList[MessageResponse]]]
  def getDepartures(eoriNumber: EORINumber): EitherT[Future, MongoError, Option[NonEmptyList[DepartureWithoutMessages]]]
  def updateMessages(departureId: DepartureId, message: Message, mrn: Option[MovementReferenceNumber]): EitherT[Future, MongoError, MessageId]
}

object DeparturesRepositoryImpl {
  val EPOCH_TIME: LocalDateTime = LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC)
}

class DeparturesRepositoryImpl @Inject() (
  appConfig: AppConfig,
  mongoComponent: MongoComponent,
  clock: Clock
)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[Departure](
      mongoComponent = mongoComponent,
      collectionName = "departure_movements",
      domainFormat = MongoFormats.departureFormat,
      indexes = Seq(
        IndexModel(Indexes.ascending("updated"), IndexOptions().expireAfter(appConfig.documentTtl, TimeUnit.SECONDS))
      ),
      extraCodecs = Seq(
        Codecs.playFormatCodec(MongoFormats.departureFormat),
        Codecs.playFormatCodec(MongoFormats.departureWithoutMessagesFormat),
        Codecs.playFormatCodec(MongoFormats.messageResponseFormat),
        Codecs.playFormatCodec(MongoFormats.messageFormat),
        Codecs.playFormatCodec(MongoFormats.departureIdFormat),
        Codecs.playFormatCodec(MongoFormats.mrnFormat),
        Codecs.playFormatCodec(MongoFormats.offsetDateTimeFormat)
      )
    )
    with DeparturesRepository
    with Logging
    with CommonFormats {

  def insert(departure: Departure): EitherT[Future, MongoError, Unit] =
    mongoRetry(Try(collection.insertOne(departure)) match {
      case Success(obs) =>
        obs.toFuture().map {
          result =>
            if (result.wasAcknowledged()) {
              Right(())
            } else {
              Left(InsertNotAcknowledged(s"Insert failed for departure $departure"))
            }
        }
      case Failure(NonFatal(ex)) =>
        Future.successful(Left(UnexpectedError(Some(ex))))
    })

  def getDepartureWithoutMessages(eoriNumber: EORINumber, departureId: DepartureId): EitherT[Future, MongoError, Option[DepartureWithoutMessages]] = {

    val selector   = mAnd(mEq("_id", departureId.value), mEq("enrollmentEORINumber", eoriNumber.value))
    val projection = DepartureWithoutMessages.projection

    val aggregates = Seq(
      Aggregates.filter(selector),
      Aggregates.project(projection)
    )

    mongoRetry(Try(collection.aggregate[DepartureWithoutMessages](aggregates)) match {
      case Success(obs) =>
        obs.headOption().map {
          opt => Right(opt)
        }
      case Failure(NonFatal(ex)) =>
        Future.successful(Left(UnexpectedError(Some(ex))))
    })
  }

  def getMessages(
    eoriNumber: EORINumber,
    departureId: DepartureId,
    receivedSince: Option[OffsetDateTime]
  ): EitherT[Future, MongoError, Option[NonEmptyList[MessageResponse]]] = {

    val projection = MessageResponse.projection

    val selector = mAnd(
      mEq("_id", departureId.value),
      mEq("enrollmentEORINumber", eoriNumber.value),
      mGte("messages.received", receivedSince.map(_.toLocalDateTime).getOrElse(EPOCH_TIME))
    )

    val aggregates =
      Seq(
        Aggregates.filter(selector),
        Aggregates.unwind("$messages"),
        Aggregates.replaceRoot("$messages"),
        Aggregates.sort(descending("received")),
        Aggregates.project(projection)
      )

    mongoRetry(Try(collection.aggregate[MessageResponse](aggregates)) match {
      case Success(obs) =>
        obs
          .toFuture()
          .map(
            response => Right(NonEmptyList.fromList(response.toList))
          )
      case Failure(NonFatal(ex)) =>
        Future.successful(Left(UnexpectedError(Some(ex))))
    })

  }

  def getSingleMessage(eoriNumber: EORINumber, departureId: DepartureId, messageId: MessageId): EitherT[Future, MongoError, Option[MessageResponse]] = {
    val selector          = mAnd(mEq("_id", departureId.value), mEq("messages.id", messageId.value), mEq("enrollmentEORINumber", eoriNumber.value))
    val secondarySelector = mEq("messages.id", messageId.value)
    val aggregates =
      Seq(Aggregates.filter(selector), Aggregates.unwind("$messages"), Aggregates.filter(secondarySelector), Aggregates.replaceRoot("$messages"))

    mongoRetry(Try(collection.aggregate[MessageResponse](aggregates)) match {
      case Success(obs) =>
        obs.headOption().map {
          opt => Right(opt)
        }
      case Failure(NonFatal(ex)) =>
        Future.successful(Left(UnexpectedError(Some(ex))))
    })
  }

  private def mongoRetry[A](func: Future[Either[MongoError, A]]): EitherT[Future, MongoError, A] =
    EitherT {
      retry(
        attempts = appConfig.mongoRetryAttempts,
        attempt = () => func
      )
    }

  def getDepartures(eoriNumber: EORINumber): EitherT[Future, MongoError, Option[NonEmptyList[DepartureWithoutMessages]]] = {
    val selector: Bson = mEq("enrollmentEORINumber", eoriNumber.value)
    val projection     = DepartureWithoutMessages.projection

    val aggregates = Seq(
      Aggregates.filter(selector),
      Aggregates.sort(descending("updated")),
      Aggregates.project(projection)
    )

    mongoRetry(Try(collection.aggregate[DepartureWithoutMessages](aggregates)) match {
      case Success(obs) =>
        obs
          .toFuture()
          .map(
            response => Right(NonEmptyList.fromList(response.toList))
          )

      case Failure(NonFatal(ex)) =>
        Future.successful(Left(UnexpectedError(Some(ex))))
    })

  }

  def updateMessages(departureId: DepartureId, message: Message, mrn: Option[MovementReferenceNumber]): EitherT[Future, MongoError, MessageId] = {

    val filter: Bson = mEq(departureId)

    val setUpdated   = mSet("updated", OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC))
    val pushMessages = mPush("messages", message)

    val combined = Seq(setUpdated, pushMessages) ++ mrn
      .map(
        x => Seq(mSet("movementReferenceNumber", x))
      )
      .getOrElse(Seq())

    mongoRetry(Try(collection.updateOne(filter, mCombine(combined: _*))) match {
      case Success(obs) =>
        obs.toFuture().map {
          result =>
            if (result.wasAcknowledged()) {
              if (result.getModifiedCount == 0) Left(DocumentNotFound(s"No departure found with the given id: ${departureId.value}"))
              else Right(message.id)
            } else {
              Left(UpdateNotAcknowledged(s"Message update failed for departure: $departureId"))
            }
        }
      case Failure(NonFatal(ex)) =>
        Future.successful(Left(UnexpectedError(Some(ex))))
    })
  }

}
