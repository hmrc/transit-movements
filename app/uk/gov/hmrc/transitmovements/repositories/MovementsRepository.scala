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

package uk.gov.hmrc.transitmovements.repositories

import akka.pattern.retry
import cats.data.EitherT
import com.google.inject.ImplementedBy
import com.mongodb.client.model.Filters.empty
import com.mongodb.client.model.Filters.{and => mAnd}
import com.mongodb.client.model.Filters.{eq => mEq}
import com.mongodb.client.model.Filters.{gte => mGte}
import com.mongodb.client.model.Filters.{lte => mLte}
import com.mongodb.client.model.Filters.{regex => mRegex}
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.{combine => mCombine}
import com.mongodb.client.model.Updates.{push => mPush}
import com.mongodb.client.model.Updates.{set => mSet}
import org.bson.conversions.Bson
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Sorts.descending
import org.mongodb.scala.model._
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json._
import uk.gov.hmrc.transitmovements.config.AppConfig
import uk.gov.hmrc.transitmovements.models._
import uk.gov.hmrc.transitmovements.models.formats.CommonFormats
import uk.gov.hmrc.transitmovements.models.formats.MongoFormats
import uk.gov.hmrc.transitmovements.models.mongo.MongoMessage
import uk.gov.hmrc.transitmovements.models.mongo.MongoMessageUpdateData
import uk.gov.hmrc.transitmovements.models.mongo.MongoMovement
import uk.gov.hmrc.transitmovements.models.mongo.MongoPaginatedMessages
import uk.gov.hmrc.transitmovements.models.mongo.MongoPaginatedMovements
import uk.gov.hmrc.transitmovements.repositories.MovementsRepositoryImpl.EPOCH_TIME
import uk.gov.hmrc.transitmovements.services.errors.MongoError
import uk.gov.hmrc.transitmovements.services.errors.MongoError._

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import scala.annotation.nowarn
import scala.concurrent._
import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

@ImplementedBy(classOf[MovementsRepositoryImpl])
trait MovementsRepository {
  def insert(movement: MongoMovement): EitherT[Future, MongoError, Unit]

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
    message: MongoMessage,
    mrn: Option[MovementReferenceNumber],
    received: OffsetDateTime
  ): EitherT[Future, MongoError, Unit]

  def updateMessage(
    movementId: MovementId,
    messageId: MessageId,
    message: MongoMessageUpdateData,
    received: OffsetDateTime
  ): EitherT[Future, MongoError, Unit]

  def getMovementWithoutMessages(
    eoriNumber: EORINumber,
    movementId: MovementId,
    movementType: MovementType
  ): EitherT[Future, MongoError, MongoMovement]

  def getSingleMessage(
    eoriNumber: EORINumber,
    movementId: MovementId,
    messageId: MessageId,
    movementType: MovementType
  ): EitherT[Future, MongoError, MongoMessage]

  def getMessages(
    eoriNumber: EORINumber,
    movementId: MovementId,
    movementType: MovementType,
    received: Option[OffsetDateTime],
    page: Option[PageNumber] = None,
    count: Option[ItemCount] = None,
    receivedUntil: Option[OffsetDateTime] = None
  ): EitherT[Future, MongoError, MongoPaginatedMessages]

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
  ): EitherT[Future, MongoError, MongoPaginatedMovements]
}

object MovementsRepositoryImpl {
  val EPOCH_TIME: LocalDateTime = LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC)
}

@Singleton
@nowarn("msg=It would fail on the following input: Failure\\(_\\)") // this would be fatal exceptions -- we don't want to catch those
class MovementsRepositoryImpl @Inject() (
  appConfig: AppConfig,
  mongoComponent: MongoComponent,
  mongoFormats: MongoFormats
)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[MongoMovement](
      mongoComponent = mongoComponent,
      collectionName = "movements",
      domainFormat = mongoFormats.movementFormat,
      indexes = Seq(
        IndexModel(Indexes.ascending("updated"), IndexOptions().expireAfter(appConfig.documentTtl, TimeUnit.SECONDS)),
        IndexModel(Indexes.ascending("movementReferenceNumber"), IndexOptions().background(true))
      ),
      extraCodecs = Seq(
        Codecs.playFormatCodec(mongoFormats.movementFormat),
        Codecs.playFormatCodec(mongoFormats.messageFormat),
        Codecs.playFormatCodec(mongoFormats.movementIdFormat),
        Codecs.playFormatCodec(mongoFormats.mrnFormat),
        Codecs.playFormatCodec(mongoFormats.offsetDateTimeFormat),
        Codecs.playFormatCodec(mongoFormats.eoriNumberFormat),
        Codecs.playFormatCodec(mongoFormats.lrnFormat),
        Codecs.playFormatCodec(mongoFormats.messageSenderFormat),
        Codecs.playFormatCodec(mongoFormats.paginationMovementSummaryFormat),
        Codecs.playFormatCodec(mongoFormats.paginationMessageSummaryFormat)
      )
    )
    with MovementsRepository
    with Logging
    with CommonFormats {

  override def insert(movement: MongoMovement): EitherT[Future, MongoError, Unit] =
    mongoRetry(Try(collection.insertOne(movement)) match {
      case Success(obs) =>
        obs.toFuture().map {
          result =>
            if (result.wasAcknowledged()) {
              Right(())
            } else {
              Left(InsertNotAcknowledged(s"Insert failed for movement $movement"))
            }
        }
      case Failure(NonFatal(ex)) =>
        Future.successful(Left(UnexpectedError(Some(ex))))
    })

  override def getMovementWithoutMessages(
    eoriNumber: EORINumber,
    movementId: MovementId,
    movementType: MovementType
  ): EitherT[Future, MongoError, MongoMovement] = {

    val selector = mAnd(
      mEq("_id", movementId.value),
      mEq("enrollmentEORINumber", eoriNumber.value),
      mEq("movementType", movementType.value)
    )
    val projection = MongoMovement.withoutMovementsProjection

    val aggregates = Seq(
      Aggregates.filter(selector),
      Aggregates.project(projection)
    )

    mongoRetry(Try(collection.aggregate[MongoMovement](aggregates)) match {
      case Success(obs) =>
        obs
          .headOption()
          .map {
            case Some(opt) => Right(opt)
            case None      => Left(DocumentNotFound(s"No movement found with the given id: ${movementId.value}"))
          }
      case Failure(NonFatal(ex)) =>
        Future.successful(Left(UnexpectedError(Some(ex))))
    })
  }

  override def getMessages(
    eoriNumber: EORINumber,
    movementId: MovementId,
    movementType: MovementType,
    receivedSince: Option[OffsetDateTime],
    page: Option[PageNumber] = None,
    count: Option[ItemCount] = None,
    receivedUntil: Option[OffsetDateTime] = None
  ): EitherT[Future, MongoError, MongoPaginatedMessages] = {

    val selector = mAnd(
      mEq("_id", movementId.value),
      mEq("enrollmentEORINumber", eoriNumber.value),
      mEq("movementType", movementType.value)
    )

    val dateTimeSelector: Bson = mAnd(
      mGte("messages.received", receivedSince.map(_.toLocalDateTime).getOrElse(EPOCH_TIME)),
      mLte("messages.received", receivedUntil.map(_.toLocalDateTime).getOrElse(OffsetDateTime.now()))
    )
    val filterAggregates = Seq(
      Aggregates.filter(selector),
      Aggregates.unwind("$messages"),
      Aggregates.filter(dateTimeSelector),
      Aggregates.replaceRoot("$messages")
    )
    val (from, countNumber) = indices(page, count)

    val aggregates =
      filterAggregates ++ Seq(
        Aggregates.sort(descending("received")),
        Aggregates.skip(from),
        Aggregates.limit(countNumber),
        Aggregates.project(MongoMessage.simpleMetadataProjection)
      )

    for {
      perPageMessages <- filterPerPage[MongoMessage](aggregates)
      totalCount      <- countItems(filterAggregates)
    } yield MongoPaginatedMessages(TotalCount(totalCount), perPageMessages)

  }

  private def filterPerPage[R: ClassTag](aggregates: Seq[Bson]): EitherT[Future, MongoError, Vector[R]] =
    mongoRetry(Try(collection.aggregate[R](aggregates)) match {
      case Success(obs) =>
        obs
          .toFuture()
          .map {
            response => Right(response.toVector)
          }

      case Failure(NonFatal(ex)) =>
        Future.successful(Left(UnexpectedError(Some(ex))))
    })

  override def getSingleMessage(
    eoriNumber: EORINumber,
    movementId: MovementId,
    messageId: MessageId,
    movementType: MovementType
  ): EitherT[Future, MongoError, MongoMessage] = {

    val selector = mAnd(
      mEq("_id", movementId.value),
      mEq("messages.id", messageId.value),
      mEq("enrollmentEORINumber", eoriNumber.value),
      mEq("movementType", movementType.value)
    )
    val secondarySelector = mEq("messages.id", messageId.value)
    val aggregates =
      Seq(
        Aggregates.filter(selector),
        Aggregates.unwind("$messages"),
        Aggregates.filter(secondarySelector),
        Aggregates.replaceRoot("$messages")
      )

    mongoRetry(Try(collection.aggregate[MongoMessage](aggregates)) match {
      case Success(obs) =>
        obs.headOption().map {
          case Some(opt) => Right(opt)
          case None      => Left(DocumentNotFound(s"Message ID ${messageId.value} for movement ID ${movementId.value} was not found"))
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

  override def getMovements(
    eoriNumber: EORINumber,
    movementType: MovementType,
    updatedSince: Option[OffsetDateTime],
    movementEORI: Option[EORINumber],
    movementReferenceNumber: Option[MovementReferenceNumber],
    page: Option[PageNumber] = None,
    count: Option[ItemCount] = None,
    receivedUntil: Option[OffsetDateTime] = None,
    localReferenceNumber: Option[LocalReferenceNumber]
  ): EitherT[Future, MongoError, MongoPaginatedMovements] = {

    val dateTimeFilter: Bson = mAnd(
      mGte("updated", updatedSince.map(_.toLocalDateTime).getOrElse(EPOCH_TIME)),
      mLte("updated", receivedUntil.map(_.toLocalDateTime).getOrElse(OffsetDateTime.now()))
    )
    val selector: Bson = mAnd(
      mEq("enrollmentEORINumber", eoriNumber.value),
      mEq("movementType", movementType.value),
      dateTimeFilter,
      movementEORIFilter(movementEORI),
      movementMRNFilter(movementReferenceNumber),
      movementLRNFilter(localReferenceNumber)
    )
    val (from, itemCount) = indices(page, count)
    val filterAggregates  = Seq(Aggregates.filter(selector))
    val aggregates = filterAggregates ++ Seq(
      Aggregates.sort(descending("updated")),
      Aggregates.skip(from),
      Aggregates.limit(itemCount),
      Aggregates.project(MongoMessage.simpleMetadataProjection)
    )
    for {
      perPageMovements <- filterPerPage[MongoMovement](aggregates)
      totalCount       <- countItems(filterAggregates)
    } yield MongoPaginatedMovements(TotalCount(totalCount), perPageMovements)

  }

  private def countItems(totalDocument: Seq[Bson]): EitherT[Future, MongoError, Long] =
    mongoRetry(Try(collection.aggregate[BsonDocument](totalDocument ++ Seq(Aggregates.count()))) match {
      case Success(obs) =>
        obs
          .toFuture()
          .map(
            _.headOption
              .map(
                bsonDocument => Right(bsonDocument.get("count").asNumber().longValue())
              )
              .getOrElse(Right(0L))
          )
      case Failure(NonFatal(ex)) =>
        Future.successful(Left(UnexpectedError(Some(ex))))
    })

  private def indices(pageNumber: Option[PageNumber], itemCount: Option[ItemCount]): (Int, Int) = {
    val startIndex = pageNumber.flatMap(
      page =>
        itemCount.map(
          count => (page.value - 1) * count.value
        )
    )
    val count = itemCount.fold(Int.MaxValue - 1)(_.value)

    (startIndex.getOrElse(0), count)
  }

  private def movementEORIFilter(movementEORI: Option[EORINumber]): Bson =
    movementEORI match {
      case Some(movementEORI) => mAnd(mEq("movementEORINumber", movementEORI.value))
      case _                  => empty()
    }

  private def movementMRNFilter(movementReferenceNumber: Option[MovementReferenceNumber]): Bson =
    movementReferenceNumber match {
      case Some(movementReferenceNumber) => mAnd(mRegex("movementReferenceNumber", s"\\Q${movementReferenceNumber.value}\\E", "i"))
      case _                             => empty()
    }

  private def movementLRNFilter(localReferenceNumber: Option[LocalReferenceNumber]): Bson =
    localReferenceNumber match {
      case Some(localReferenceNumber) => mAnd(mRegex("localReferenceNumber", s"\\Q${localReferenceNumber.value}\\E", "i"))
      case _                          => empty()
    }

  override def attachMessage(
    movementId: MovementId,
    message: MongoMessage,
    mrn: Option[MovementReferenceNumber],
    received: OffsetDateTime
  ): EitherT[Future, MongoError, Unit] = {

    val filter: Bson = mEq(movementId)

    val setUpdated   = mSet("updated", received)
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
              if (result.getModifiedCount == 0) Left(DocumentNotFound(s"No movement found with the given id: ${movementId.value}"))
              else Right(())
            } else {
              Left(UpdateNotAcknowledged(s"Message update failed for movement: $movementId"))
            }
        }
      case Failure(NonFatal(ex)) =>
        Future.successful(Left(UnexpectedError(Some(ex))))
    })
  }

  override def updateMessage(
    movementId: MovementId,
    messageId: MessageId,
    message: MongoMessageUpdateData,
    received: OffsetDateTime
  ): EitherT[Future, MongoError, Unit] = {

    val filter: Bson = mEq(movementId.value)

    val setUpdated = mSet("updated", received)
    val setStatus  = mSet("messages.$[element].status", message.status.toString)

    val arrayFilters = new UpdateOptions().arrayFilters(Collections.singletonList(Filters.in("element.id", messageId.value)))

    val combined = Seq(setUpdated, setStatus) ++
      createSet("messages.$[element].uri", message.objectStoreURI.map(_.value)) ++
      createSet("messages.$[element].body", message.body) ++
      createSet("messages.$[element].size", message.size) ++
      createSet("messages.$[element].messageType", message.messageType.map(_.code)) ++
      createSet("messages.$[element].generated", message.generationDate)

    executeUpdate(movementId, filter, combined, arrayFilters)
  }

  private def createSet[A](path: String, value: Option[A]): Seq[Bson] =
    value
      .map(
        v => Seq(mSet(path, v))
      )
      .getOrElse(Seq.empty)

  override def updateMovement(
    movementId: MovementId,
    movementEORI: Option[EORINumber],
    mrn: Option[MovementReferenceNumber],
    lrn: Option[LocalReferenceNumber],
    messageSender: Option[MessageSender],
    updated: OffsetDateTime
  ): EitherT[Future, MongoError, Unit] = {
    val filter: Bson = mEq(movementId.value)

    val combined: Seq[Bson] =
      movementEORI
        .map(
          e => Seq(mSet("movementEORINumber", e.value))
        )
        .getOrElse(Seq.empty[Bson]) ++
        mrn
          .map(
            e => Seq(mSet("movementReferenceNumber", e.value))
          )
          .getOrElse(Seq.empty[Bson]) ++
        lrn
          .map(
            e => Seq(mSet("localReferenceNumber", e.value))
          )
          .getOrElse(Seq.empty[Bson]) ++
        messageSender
          .map(
            e => Seq(mSet("messageSender", e.value))
          )
          .getOrElse(Seq.empty[Bson])

    // If we don't have to update anything, don't bother going to Mongo.
    if (combined.isEmpty) EitherT.rightT(())
    else executeUpdate(movementId, filter, combined ++ Seq(mSet("updated", updated)))
  }

  private def executeUpdate(
    movementId: MovementId,
    filter: Bson,
    updates: Seq[Bson],
    updateOptions: UpdateOptions = new UpdateOptions()
  ): EitherT[Future, MongoError, Unit] =
    mongoRetry(Try(collection.updateOne(filter, mCombine(updates: _*), updateOptions)) match {
      case Success(obs) =>
        obs.toFuture().map {
          result =>
            if (result.wasAcknowledged()) {
              if (result.getModifiedCount == 0) Left(DocumentNotFound(s"No movement found with the given id: ${movementId.value}"))
              else Right(())
            } else {
              Left(UpdateNotAcknowledged(s"Message update failed for movement: $movementId"))
            }
        }
      case Failure(NonFatal(ex)) =>
        Future.successful(Left(UnexpectedError(Some(ex))))
    })

}
