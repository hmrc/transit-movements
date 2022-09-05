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

import akka.stream.Materializer
import cats.data.NonEmptyList
import com.google.inject.ImplementedBy
import uk.gov.hmrc.transitmovements.models.values.ShortUUID
import uk.gov.hmrc.transitmovements.models.DeclarationData
import uk.gov.hmrc.transitmovements.models.Departure
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.Message

import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject

@ImplementedBy(classOf[DepartureFactoryImpl])
trait DepartureFactory {

  def create(
    eori: EORINumber,
    declarationData: DeclarationData,
    message: Message
  ): Departure
}

class DepartureFactoryImpl @Inject() (
  clock: Clock,
  random: SecureRandom
)(implicit
  val materializer: Materializer
) extends DepartureFactory {

  def create(
    eori: EORINumber,
    declarationData: DeclarationData,
    message: Message
  ): Departure =
    Departure(
      _id = DepartureId(ShortUUID.next(clock, random)),
      enrollmentEORINumber = eori,
      movementEORINumber = declarationData.movementEoriNumber,
      movementReferenceNumber = None,
      created = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC),
      updated = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC),
      messages = NonEmptyList.one(message)
    )

}
