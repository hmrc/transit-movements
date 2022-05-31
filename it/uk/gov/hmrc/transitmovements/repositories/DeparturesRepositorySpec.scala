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

import org.mongodb.scala.model.Filters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.Application
import play.api.Logging
import play.api.test.DefaultAwaitTimeout
import play.api.test.FutureAwaits
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.transitmovements.config.AppConfig
import uk.gov.hmrc.transitmovements.models.Departure
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.EORINumber

import java.time.OffsetDateTime
import java.time.ZoneOffset
import scala.concurrent.ExecutionContext.Implicits.global

class DeparturesRepositorySpec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with FutureAwaits
    with DefaultAwaitTimeout
    with Logging
    with DefaultPlayMongoRepositorySupport[Departure] {

  val instant: OffsetDateTime = OffsetDateTime.of(2022, 5, 25, 16, 0, 0, 0, ZoneOffset.UTC)

  override lazy val mongoComponent: MongoComponent = {
    val databaseName: String = "test-" + this.getClass.getSimpleName
    val mongoUri: String     = s"mongodb://localhost:27017/$databaseName?retryWrites=false"
    MongoComponent(mongoUri)
  }

  implicit lazy val app: Application = GuiceApplicationBuilder().configure().build()
  private val appConfig              = app.injector.instanceOf[AppConfig]

  override lazy val repository = new DeparturesRepositoryImpl(appConfig, mongoComponent)

  "DepartureMovementRepository" should "have the correct name" in {
    repository.collectionName shouldBe "departure_movements"
  }

  it should "insert the given departure" in {

    val departure =
      Departure(
        DepartureId("1"),
        enrollmentEORINumber = EORINumber("111"),
        movementEORINumber = EORINumber("222"),
        movementReferenceNumber = None,
        created = instant,
        updated = instant,
        messages = Seq.empty
      )

    val declarationResponse = await(
      repository.insert(departure).value
    )

    val firstItem = await {
      repository.collection.find(Filters.eq("_id", "1")).first().toFuture()
    }

    firstItem._id.value should be("1")
  }
}
