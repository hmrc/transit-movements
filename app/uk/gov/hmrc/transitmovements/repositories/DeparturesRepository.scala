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
import org.mongodb.scala.result.InsertOneResult
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.transitmovements.models.formats.MongoFormats
import uk.gov.hmrc.transitmovements.models.responses.DeclarationResponse
import uk.gov.hmrc.transitmovements.models.Departure
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.MovementMessageId

import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

@ImplementedBy(classOf[DeparturesRepositoryImpl])
trait DeparturesRepository {
  def insert(request: Departure): EitherT[Future, Throwable, DeclarationResponse]
}

class DeparturesRepositoryImpl @Inject() (
  mongoComponent: MongoComponent
)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[Departure](
      mongoComponent = mongoComponent,
      collectionName = "departure_movements",
      domainFormat = MongoFormats.departureFormat,
      indexes = Seq()
    )
    with DeparturesRepository
    with Logging {

  def insert(departure: Departure): EitherT[Future, Throwable, DeclarationResponse] =
    EitherT {
      retry(
        attempts = 0,
        attempt = () => insertDocument(departure)
      )
    }

  private def insertDocument(departure: Departure): Future[Either[Throwable, DeclarationResponse]] =
    Try(collection.insertOne(departure)) match {
      case Success(value) =>
        value
          .head()
          .map(
            result => Right(createResponse(result))
          )
      case Failure(ex) =>
        Future.successful(Left(ex))
    }

  private def createResponse(result: InsertOneResult): DeclarationResponse =
    DeclarationResponse(DepartureId(result.getInsertedId.toString), MovementMessageId("not-implemented"))

}
