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

package repositories

import akka.pattern.retry
import akka.util.ByteString
import com.google.inject.ImplementedBy
import com.mongodb.reactivestreams.client.ClientSession
import models.Departure
import models.formats.MongoFormats
import models.request.DeclarationDataRequest
import models.values.DepartureId
import org.mongodb.scala.model.IndexModel
import org.mongodb.scala.model.IndexOptions
import org.mongodb.scala.model.Indexes
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[DeparturesRepositoryImpl])
trait DeparturesRepository {
  def insertDeparture(
    session: ClientSession,
    request: DeclarationDataRequest
  ): Future[DepartureId]
}

@Singleton
class DeparturesRepositoryImpl @Inject() (
  mongoComponent: MongoComponent,
  clock: Clock,
  random: SecureRandom
)(implicit ec: ExecutionContext)
  extends PlayMongoRepository[Departure](
    mongoComponent = mongoComponent,
    collectionName = DeparturesRepository.collectionName,
    domainFormat = MongoFormats.departureFormat,
    indexes = Seq(
      IndexModel(
        Indexes.ascending("updatedAt"),
        IndexOptions().background(false)
      )
    )
  )
  with DeparturesRepository
  with Logging {

  def insertDeparture(
    session: ClientSession,
    request: DeclarationDataRequest
  ): Future[DepartureId] = {
    retry(
      attempts = 3,
      attempt = { () =>
        val departureId = DepartureId.next(clock, random)

        val departure = Departure(
          departureId,
          request.eoriNumber,
          request.localReferenceNumber,
          movementReferenceNumber = None,
          createdAt = request.preparationDateTime,
          updatedAt = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC)
        )

        val insert =
          collection
            .insertOne(session, departure)
            .headOption()
            .map { result =>
              DepartureId(ByteString(result.get.getInsertedId.asBinary.getData))
            }

        insert
      }
    )
  }
}

object DeparturesRepository {
  val collectionName = "departures"
}
