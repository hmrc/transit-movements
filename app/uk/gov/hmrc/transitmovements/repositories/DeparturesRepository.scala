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
import com.google.inject.ImplementedBy
import com.mongodb.client.model.Filters.{eq => mongoEq}
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
import uk.gov.hmrc.transitmovements.models.formats.MongoFormats
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
  def get(departureId: DepartureId): EitherT[Future, MongoError, Option[Departure]]
}

class DeparturesRepositoryImpl @Inject() (
  appConfig: AppConfig,
  mongoComponent: MongoComponent
)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[Departure](
      mongoComponent = mongoComponent,
      collectionName = "departure_movements",
      domainFormat = MongoFormats.departureFormat,
      indexes = Seq(
        IndexModel(Indexes.ascending("updated"), IndexOptions().expireAfter(appConfig.documentTtl, TimeUnit.SECONDS))
      ),
      extraCodecs = Seq(
        Codecs.playFormatCodec(MongoFormats.departureFormat)
      )
    )
    with DeparturesRepository
    with Logging {

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

  def get(departureId: DepartureId): EitherT[Future, MongoError, Option[Departure]] =
    mongoRetry(Try(collection.find(mongoEq("_id", departureId.value))) match {
      case Success(obs) =>
        obs.headOption().map {
          opt => Right(opt)
        }
      case Failure(NonFatal(ex)) =>
        Future.successful(Left(UnexpectedError(Some(ex))))
    })

  private def mongoRetry[A](func: Future[Either[MongoError, A]]): EitherT[Future, MongoError, A] =
    EitherT {
      retry(
        attempts = appConfig.mongoRetryAttempts,
        attempt = () => func
      )
    }

}
