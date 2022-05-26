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
import uk.gov.hmrc.transitmovements.models.DeclarationData
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.Departure
import uk.gov.hmrc.transitmovements.models.DepartureId
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.transitmovements.models.formats.MongoFormats
import uk.gov.hmrc.transitmovements.models.values.BytesToHex
import uk.gov.hmrc.transitmovements.models.values.ShortUUID

import java.security.SecureRandom
import java.time.Clock
import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[DeparturesRepositoryImpl])
trait DeparturesRepository {
  def insert(request: Departure): EitherT[Future, Error, DeclarationResponse]
}

class DeparturesRepositoryImpl @Inject()(
  mongoComponent: MongoComponent,
  clock: Clock,
  random: SecureRandom
)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[Departure](
      mongoComponent = mongoComponent,
      collectionName = DeparturesRepository.collectionName,
      domainFormat = MongoFormats.movementFormat,
      indexes = Seq()
    )
    with DeparturesRepository
    with Logging {


  def insert(departure: Departure): EitherT[Future, Error, DeclarationResponse] =
    retry(
      attempts = 0,
      attempt = {
        () =>
          val nextId     = ShortUUID.next(clock, random)
          val movementId = DepartureId(BytesToHex.toHex(nextId))


          collection
            .insertOne(departure.copy(_id = movementId))
            .headOption()
            .map {
              result =>
                DeclarationResponse(
                  DepartureId(result.get.getInsertedId.toString)
                )
            }
      }
    )
}

object DeparturesRepository {
  val collectionName = "departure_movements"
}
