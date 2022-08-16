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

import cats.data.NonEmptyList
import org.mongodb.scala.model.Filters
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.OptionValues
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
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.Departure
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.DepartureWithoutMessages
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageType
import cats.data.NonEmptyList

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
    with DefaultPlayMongoRepositorySupport[Departure]
    with ModelGenerators
    with OptionValues {

  val instant: OffsetDateTime = OffsetDateTime.of(2022, 5, 25, 16, 0, 0, 0, ZoneOffset.UTC)

  override lazy val mongoComponent: MongoComponent = {
    val databaseName: String = "test-departures_movements"
    val mongoUri: String     = s"mongodb://localhost:27017/$databaseName?retryWrites=false"
    MongoComponent(mongoUri)
  }

  implicit lazy val app: Application = GuiceApplicationBuilder().configure().build()
  private val appConfig              = app.injector.instanceOf[AppConfig]

  override lazy val repository = new DeparturesRepositoryImpl(appConfig, mongoComponent)

  "DepartureMovementRepository" should "have the correct name" in {
    repository.collectionName shouldBe "departure_movements"
  }

  "insert" should "add the given departure to the database" in {

    val departure = arbitrary[Departure].sample.value.copy(_id = DepartureId("2"))

    await(
      repository.insert(departure).value
    )

    val firstItem = await {
      repository.collection.find(Filters.eq("_id", "2")).first().toFuture()
    }

    firstItem._id.value should be("2")
  }

  "getDepartureWithoutMessages" should "return DepartureWithoutMessages if it exists" in {
    val departure = arbitrary[Departure].sample.value

    await(repository.insert(departure).value)

    val result = await(repository.getDepartureWithoutMessages(departure.enrollmentEORINumber, departure._id).value)
    result.right.get.get should be(DepartureWithoutMessages.fromDeparture(departure))
  }

  "getDepartureWithoutMessages" should "return none if the departure doesn't exist" in {
    val departure = arbitrary[Departure].sample.value.copy(_id = DepartureId("1"))

    await(repository.insert(departure).value)

    val result = await(repository.getDepartureWithoutMessages(departure.enrollmentEORINumber, DepartureId("2")).value)
    result.right.get.isEmpty should be(true)
  }

  "getMessage" should "return message if it exists" in {
    val departure = arbitrary[Departure].sample.value

    await(repository.insert(departure).value)

    val result = await(repository.getMessage(departure.enrollmentEORINumber, departure._id, departure.messages.head.id).value)
    result.right.get.get should be(departure.messages.head)
  }

  "getMessage" should "return none if the message doesn't exist" in {
    val departure = arbitrary[Departure].sample.value

    await(repository.insert(departure).value)

    val result = await(repository.getMessage(departure.enrollmentEORINumber, departure._id, MessageId("X")).value)
    result.right.get.isEmpty should be(true)
  }

  "getDepartureIds" should
    "return a list of departure ids for the supplied EORI sorted by last updated, latest first" in new DBSetup {
      val result = await(repository.getDepartureIds(eoriGB).value)

      result.right.get.value should be(NonEmptyList(DepartureId("10004"), List(DepartureId("10001"))))
    }

  it should "return no departure ids for an EORI that doesn't exist" in new DBSetup {
    val result = await(repository.getDepartureIds(EORINumber("FR999")).value)

    result.right.get should be(None)
  }

  it should "return no departure ids when the db is empty" in new DBSetup {
    await(repository.collection.drop().toFuture())
    val result = await(repository.getDepartureIds(EORINumber("FR999")).value)

    result.right.get should be(None)
  }

  trait DBSetup {

    val eoriGB  = EORINumber("GB00001")
    val eoriXI  = EORINumber("XI00001")
    val timeNow = OffsetDateTime.now()

    val departure1 = Departure(
      _id = DepartureId("10001"),
      enrollmentEORINumber = eoriGB,
      movementEORINumber = EORINumber("20001"),
      movementReferenceNumber = None,
      created = timeNow,
      updated = timeNow,
      messages = NonEmptyList(
        Message(
          id = MessageId("00011"),
          received = timeNow,
          generated = timeNow,
          messageType = MessageType.DeclarationData,
          triggerId = None,
          url = None,
          body = None
        ),
        tail = List.empty
      )
    )

    val departure2 = departure1.copy(_id = DepartureId("10002"), enrollmentEORINumber = eoriXI, updated = OffsetDateTime.now().plusMinutes(1))
    val departure3 = departure1.copy(_id = DepartureId("10003"), enrollmentEORINumber = eoriXI, updated = OffsetDateTime.now().minusMinutes(3))
    val departure4 = departure1.copy(_id = DepartureId("10004"), enrollmentEORINumber = eoriGB, updated = OffsetDateTime.now().plusMinutes(1))

    //populate db in non-time order
    await(repository.insert(departure3).value)
    await(repository.insert(departure2).value)
    await(repository.insert(departure4).value)
    await(repository.insert(departure1).value)

  }
}
