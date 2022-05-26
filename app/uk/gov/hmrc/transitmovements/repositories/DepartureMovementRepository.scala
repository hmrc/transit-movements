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
import com.google.inject.ImplementedBy
import uk.gov.hmrc.transitmovements.models.DeclarationData
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.Movement
import uk.gov.hmrc.transitmovements.models.MovementId
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.transitmovements.models.formats.MongoFormats
import uk.gov.hmrc.transitmovements.models.values.BytesToHex
import uk.gov.hmrc.transitmovements.models.values.ShortUUID

import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[DepartureMovementRepositoryImpl])
trait DepartureMovementRepository {
  def insert(request: DeclarationData): Future[MovementId]
}

class DepartureMovementRepositoryImpl @Inject() (
  mongoComponent: MongoComponent,
  clock: Clock,
  random: SecureRandom
)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[Movement](
      mongoComponent = mongoComponent,
      collectionName = DepartureMovementRepository.collectionName,
      domainFormat = MongoFormats.movementFormat,
      indexes = Seq()
    )
    with DepartureMovementRepository
    with Logging {

  def insert(declarationData: DeclarationData): Future[MovementId] =
    retry(
      attempts = 0,
      attempt = {
        () =>
          val nextId     = ShortUUID.next(clock, random)
          val movementId = MovementId(BytesToHex.toHex(nextId))

          collection
            .insertOne(createMovement(movementId, declarationData))
            .headOption()
            .map {
              result =>
                MovementId(result.get.getInsertedId.toString)
            }
      }
    )

  val createMovement: (MovementId, DeclarationData) => Movement = {
    (id, declarationData) =>
      Movement(
        id,
        enrollmentEORINumber = EORINumber("111"),
        movementEORINumber = declarationData.movementEoriNumber,
        movementReferenceNumber = None,
        created = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC),
        updated = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC),
        messages = Seq.empty
      )
  }
}

object DepartureMovementRepository {
  val collectionName = "departure_movements"
}
