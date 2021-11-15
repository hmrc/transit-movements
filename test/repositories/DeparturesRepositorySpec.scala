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

import models.Departure
import models.request.DeclarationDataRequest
import models.values.CustomsOfficeNumber
import models.values.EoriNumber
import models.values.LocalReferenceNumber
import org.mongodb.scala.model.Filters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.Logging
import play.api.test.DefaultAwaitTimeout
import play.api.test.FutureAwaits
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.mongo.transaction.TransactionConfiguration

import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import scala.concurrent.ExecutionContext.Implicits.global

class DeparturesRepositorySpec
  extends AnyFlatSpec
  with Matchers
  with ScalaCheckPropertyChecks
  with FutureAwaits
  with DefaultAwaitTimeout
  with Transactions
  with Logging
  with DefaultPlayMongoRepositorySupport[Departure] {

  val fixedInstant = OffsetDateTime.of(2021, 11, 15, 16, 9, 47, 0, ZoneOffset.UTC)
  val clock        = Clock.fixed(fixedInstant.toInstant, ZoneOffset.UTC)
  val random       = new SecureRandom

  implicit val tc: TransactionConfiguration =
    TransactionConfiguration.strict

  override lazy val mongoComponent: MongoComponent = {
    val databaseName: String = "test-" + this.getClass.getSimpleName
    val mongoUri: String     = s"mongodb://localhost:27017/$databaseName"
    MongoComponent(mongoUri)
  }

  override lazy val repository = new DeparturesRepositoryImpl(
    mongoComponent,
    clock,
    random
  )

  "DeparturesRepository" should "have the correct name" in {
    repository.collectionName shouldBe "departures"
  }

  it should "insert departures based on declaration data" in {
    val eoriNumber   = EoriNumber("GB123456789000")
    val lrn          = LocalReferenceNumber("ABC1234567890")
    val prepDateTime = OffsetDateTime.now

    val request = DeclarationDataRequest(
      eoriNumber,
      prepDateTime,
      lrn,
      CustomsOfficeNumber("GB000060")
    )

    val departureId = await(withTransaction { session =>
      repository.insertDeparture(session, request)
    })

    val departures = await(find(Filters.eq("_id", departureId.id.toArray)))

    departures should not be empty

    departures.head shouldBe Departure(
      departureId,
      eoriNumber,
      lrn,
      movementReferenceNumber = None,
      prepDateTime,
      fixedInstant
    )
  }
}
