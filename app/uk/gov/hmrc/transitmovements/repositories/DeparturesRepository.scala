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
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Accumulators
import org.mongodb.scala.model.Aggregates
import org.mongodb.scala.model.IndexModel
import org.mongodb.scala.model.IndexOptions
import org.mongodb.scala.model.Indexes
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.transitmovements.config.AppConfig
import uk.gov.hmrc.transitmovements.models.Departure
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.DepartureWithoutMessages
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.formats.CommonFormats
import uk.gov.hmrc.transitmovements.models.formats.ModelFormats
import uk.gov.hmrc.transitmovements.services.errors.MongoError
import uk.gov.hmrc.transitmovements.services.errors.MongoError.InsertNotAcknowledged
import uk.gov.hmrc.transitmovements.services.errors.MongoError.UnexpectedError

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

@ImplementedBy(classOf[DeparturesRepositoryImpl])
trait DeparturesRepository {
  def insert(departure: Departure): EitherT[Future, MongoError, Unit]
  def getDepartureWithoutMessages(eoriNumber: EORINumber, departureId: DepartureId): EitherT[Future, MongoError, Option[DepartureWithoutMessages]]
  def getMessage(eoriNumber: EORINumber, departureId: DepartureId, movementId: MessageId): EitherT[Future, MongoError, Option[Message]]
  def getDepartureMessageIds(eoriNumber: EORINumber, departureId: DepartureId): EitherT[Future, MongoError, Option[NonEmptyList[MessageId]]]
}

class DeparturesRepositoryImpl @Inject() (
  appConfig: AppConfig,
  mongoComponent: MongoComponent
)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[Departure](
      mongoComponent = mongoComponent,
      collectionName = "departure_movements",
      domainFormat = ModelFormats.departureFormat,
      indexes = Seq(
        IndexModel(Indexes.ascending("updated"), IndexOptions().expireAfter(appConfig.documentTtl, TimeUnit.SECONDS))
      ),
      extraCodecs = Seq(
        Codecs.playFormatCodec(ModelFormats.departureFormat),
        Codecs.playFormatCodec(ModelFormats.messageFormat),
        Codecs.playFormatCodec(ModelFormats.departureWithoutMessagesFormat),
        Codecs.playFormatCodec(ModelFormats.messageIdFormat),
        Codecs.playFormatCodec(GetDepartureMessageIdsDTO.format)
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

  def getDepartureMessageIds(eoriNumber: EORINumber, departureId: DepartureId): EitherT[Future, MongoError, Option[NonEmptyList[MessageId]]] = {

    val selector = mAnd(mEq("_id", departureId.value), mEq("enrollmentEORINumber", eoriNumber.value))

    val aggregates =
      Seq(
        Aggregates.filter(selector),
        Aggregates.group(null, Accumulators.push("result", "$messages.id")),
        Aggregates.unwind("$result"),
        Aggregates.project(BsonDocument("_id" -> 0, "result" -> 1))
      )

    mongoRetry(Try(collection.aggregate[GetDepartureMessageIdsDTO](aggregates)) match {
      case Success(obs) =>
        obs.headOption.map {
          case Some(dto) => Right(Some(dto.result))
          case None      => Right(None)
        }
      case Failure(NonFatal(ex)) =>
        Future.successful(Left(UnexpectedError(Some(ex))))
    })

  }

  def getMessage(eoriNumber: EORINumber, departureId: DepartureId, messageId: MessageId): EitherT[Future, MongoError, Option[Message]] = {
    val selector          = mAnd(mEq("_id", departureId.value), mEq("messages.id", messageId.value), mEq("enrollmentEORINumber", eoriNumber.value))
    val secondarySelector = mEq("messages.id", messageId.value)
    val aggregates =
      Seq(Aggregates.filter(selector), Aggregates.unwind("$messages"), Aggregates.filter(secondarySelector), Aggregates.replaceRoot("$messages"))

    mongoRetry(Try(collection.aggregate[Message](aggregates)) match {
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

}
