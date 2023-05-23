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
import com.mongodb.client.model.Filters.{and => mAnd}
import com.mongodb.client.model.Filters.{eq => mEq}
import com.mongodb.client.model.Filters.{gte => mGte}
import com.mongodb.client.model.Filters.empty
import com.mongodb.client.model.Updates.{push => mPush}
import com.mongodb.client.model.Updates.{set => mSet}
import com.mongodb.client.model.Updates.{combine => mCombine}
import com.mongodb.client.model.UpdateOptions
import org.bson.conversions.Bson
import org.mongodb.scala.model._
import org.mongodb.scala.model.Sorts.descending
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json._
import uk.gov.hmrc.transitmovements.config.AppConfig
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.Movement
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.MovementType
import uk.gov.hmrc.transitmovements.models.MovementWithoutMessages
import uk.gov.hmrc.transitmovements.models.UpdateMessageData
import uk.gov.hmrc.transitmovements.models.formats.CommonFormats
import uk.gov.hmrc.transitmovements.models.formats.MongoFormats
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse
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
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

@ImplementedBy(classOf[MovementsRepositoryImpl])
trait MovementsRepository {
  def insert(movement: Movement): EitherT[Future, MongoError, Unit]

  def getMovementWithoutMessages(
    eoriNumber: EORINumber,
    movementId: MovementId,
    movementType: MovementType
  ): EitherT[Future, MongoError, MovementWithoutMessages]

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
    received: Option[OffsetDateTime]
  ): EitherT[Future, MongoError, Vector[MessageResponse]]

  def getMovements(
    eoriNumber: EORINumber,
    movementType: MovementType,
    updatedSince: Option[OffsetDateTime],
    movementEORI: Option[EORINumber],
    movementReferenceNumber: Option[MovementReferenceNumber]
  ): EitherT[Future, MongoError, Vector[MovementWithoutMessages]]

  def updateMovement(
    movementId: MovementId,
    movementEORI: Option[EORINumber],
    mrn: Option[MovementReferenceNumber],
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

}

object MovementsRepositoryImpl {
  val EPOCH_TIME: LocalDateTime = LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC)
}

@Singleton
@nowarn("msg=It would fail on the following input: Failure\\(_\\)") // this would be fatal exceptions -- we don't want to catch those
class MovementsRepositoryImpl @Inject() (
  appConfig: AppConfig,
  mongoComponent: MongoComponent
)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[Movement](
      mongoComponent = mongoComponent,
      collectionName = "movements",
      domainFormat = MongoFormats.movementFormat,
      indexes = Seq(
        IndexModel(Indexes.ascending("updated"), IndexOptions().expireAfter(appConfig.documentTtl, TimeUnit.SECONDS))
      ),
      extraCodecs = Seq(
        Codecs.playFormatCodec(MongoFormats.movementFormat),
        Codecs.playFormatCodec(MongoFormats.movementWithoutMessagesFormat),
        Codecs.playFormatCodec(MongoFormats.messageResponseFormat),
        Codecs.playFormatCodec(MongoFormats.messageFormat),
        Codecs.playFormatCodec(MongoFormats.movementIdFormat),
        Codecs.playFormatCodec(MongoFormats.mrnFormat),
        Codecs.playFormatCodec(MongoFormats.offsetDateTimeFormat),
        Codecs.playFormatCodec(MongoFormats.eoriNumberFormat)
      )
    )
    with MovementsRepository
    with Logging
    with CommonFormats {

  def insert(movement: Movement): EitherT[Future, MongoError, Unit] =
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

  def getMovementWithoutMessages(
    eoriNumber: EORINumber,
    movementId: MovementId,
    movementType: MovementType
  ): EitherT[Future, MongoError, MovementWithoutMessages] = {

    val selector = mAnd(
      mEq("_id", movementId.value),
      mEq("enrollmentEORINumber", eoriNumber.value),
      mEq("movementType", movementType.value)
    )
    val projection = MovementWithoutMessages.projection

    val aggregates = Seq(
      Aggregates.filter(selector),
      Aggregates.project(projection)
    )

    mongoRetry(Try(collection.aggregate[MovementWithoutMessages](aggregates)) match {
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

  def getMessages(
    eoriNumber: EORINumber,
    movementId: MovementId,
    movementType: MovementType,
    receivedSince: Option[OffsetDateTime]
  ): EitherT[Future, MongoError, Vector[MessageResponse]] = {

    val projection = MessageResponse.projection

    val selector = mAnd(
      mEq("_id", movementId.value),
      mEq("enrollmentEORINumber", eoriNumber.value),
      mEq("movementType", movementType.value),
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
            response => Right(response.toVector)
          )
      case Failure(NonFatal(ex)) =>
        Future.successful(Left(UnexpectedError(Some(ex))))
    })

  }

  def getSingleMessage(
    eoriNumber: EORINumber,
    movementId: MovementId,
    messageId: MessageId,
    movementType: MovementType
  ): EitherT[Future, MongoError, MessageResponse] = {

    val selector = mAnd(
      mEq("_id", movementId.value),
      mEq("messages.id", messageId.value),
      mEq("enrollmentEORINumber", eoriNumber.value),
      mEq("movementType", movementType.value)
    )
    val secondarySelector = mEq("messages.id", messageId.value)
    val aggregates =
      Seq(Aggregates.filter(selector), Aggregates.unwind("$messages"), Aggregates.filter(secondarySelector), Aggregates.replaceRoot("$messages"))

    mongoRetry(Try(collection.aggregate[MessageResponse](aggregates)) match {
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

  def getMovements(
    eoriNumber: EORINumber,
    movementType: MovementType,
    updatedSince: Option[OffsetDateTime],
    movementEORI: Option[EORINumber],
    movementReferenceNumber: Option[MovementReferenceNumber]
  ): EitherT[Future, MongoError, Vector[MovementWithoutMessages]] = {
    val selector: Bson = mAnd(
      mEq("enrollmentEORINumber", eoriNumber.value),
      mEq("movementType", movementType.value),
      mGte("updated", updatedSince.map(_.toLocalDateTime).getOrElse(EPOCH_TIME)),
      movementEORIFilter(movementEORI)
    )

    val projection = MovementWithoutMessages.projection

    val aggregates = Seq(
      Aggregates.filter(selector),
      Aggregates.sort(descending("updated")),
      Aggregates.project(projection)
    )

    mongoRetry(Try(collection.aggregate[MovementWithoutMessages](aggregates)) match {
      case Success(obs) =>
        obs
          .toFuture()
          .map {
            response =>
              movementReferenceNumber match {
                case Some(movementReferenceNumber) =>
                  Right(
                    response
                      .filter(
                        movement => partialMrnMatch(movementReferenceNumber, movement)
                      )
                      .toVector
                  )
                case _ => Right(response.toVector)
              }
          }

      case Failure(NonFatal(ex)) =>
        Future.successful(Left(UnexpectedError(Some(ex))))
    })

  }

  private def partialMrnMatch(movementReferenceNumber: MovementReferenceNumber, movement: MovementWithoutMessages) =
    movement.movementReferenceNumber.isDefined &&
      movement.movementReferenceNumber.get.value.toUpperCase.matches(s".*[${movementReferenceNumber.value.toUpperCase}].*")

  private def movementEORIFilter(movementEORI: Option[EORINumber]): Bson =
    movementEORI match {
      case Some(movementEORI) => mAnd(mEq("movementEORINumber", movementEORI.value))
      case _                  => empty()
    }

  def attachMessage(
    movementId: MovementId,
    message: Message,
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

  def updateMessage(
    movementId: MovementId,
    messageId: MessageId,
    message: UpdateMessageData,
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
