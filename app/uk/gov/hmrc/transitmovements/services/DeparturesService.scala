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

package uk.gov.hmrc.transitmovements.services

import cats.data.EitherT
import com.google.inject.ImplementedBy
import uk.gov.hmrc.transitmovements.models.responses.DeclarationResponse
import uk.gov.hmrc.transitmovements.models.values.ShortUUID
import uk.gov.hmrc.transitmovements.models.DeclarationData
import uk.gov.hmrc.transitmovements.models.Departure
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.repositories.DeparturesRepository
import uk.gov.hmrc.transitmovements.services.errors.MongoError

import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import scala.concurrent.Future

@ImplementedBy(classOf[DeparturesServiceImpl])
trait DeparturesService {
  def create(eori: EORINumber, declarationData: DeclarationData): EitherT[Future, MongoError, DeclarationResponse]
}

class DeparturesServiceImpl @Inject() (
  repository: DeparturesRepository,
  clock: Clock,
  random: SecureRandom
) extends DeparturesService {

  def create(eori: EORINumber, declarationData: DeclarationData): EitherT[Future, MongoError, DeclarationResponse] =
    repository.insert(
      Departure(
        createDepartureId(),
        enrollmentEORINumber = eori,
        movementEORINumber = declarationData.movementEoriNumber,
        movementReferenceNumber = None,
        created = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC),
        updated = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC),
        messages = Seq.empty
      )
    )

  private def createDepartureId(): DepartureId =
    DepartureId(ShortUUID.next(clock, random))

}
