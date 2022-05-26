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

package uk.gov.hmrc.transitmovements.repository

import org.mongodb.scala.bson.BsonString
import org.mongodb.scala.model.Filters
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.Logging
import play.api.test.DefaultAwaitTimeout
import play.api.test.FutureAwaits
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.transitmovements.models.DeclarationData
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.Departure
import uk.gov.hmrc.transitmovements.repositories.{DeparturesMovementRepositoryImpl, DeparturesRepositoryImpl}

import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import scala.concurrent.ExecutionContext.Implicits.global

@DoNotDiscover
class DeparturesRepositorySpec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with FutureAwaits
    with DefaultAwaitTimeout
    with Logging
    with DefaultPlayMongoRepositorySupport[Departure] {

  val instant = OffsetDateTime.of(2022, 5, 25, 16, 0, 0, 0, ZoneOffset.UTC)
  val clock   = Clock.fixed(instant.toInstant, ZoneOffset.UTC)
  val random  = new SecureRandom

//  implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  override lazy val mongoComponent: MongoComponent = {
    val databaseName: String = "test-" + this.getClass.getSimpleName
    val mongoUri: String     = s"mongodb://localhost:27017/$databaseName?retryWrites=false"
    MongoComponent(mongoUri)
  }

  override lazy val repository = new DeparturesRepositoryImpl(
    mongoComponent,
    clock,
    random
  )

  "DepartureMovementRepository" should "have the correct name" in {
    repository.collectionName shouldBe "departure_movements"
  }

  it should "insert departures based on declaration data" in {

    val declarationData = DeclarationData(EORINumber("321"), instant)

    val movementId = await(
      repository.insert(declarationData)
    )

    val movement = await {
      find(Filters.eq("_id", BsonString(movementId.value)))
    }
    movement should not be empty
  }
}
