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
import play.api.Application
import play.api.Logging
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.DefaultAwaitTimeout
import play.api.test.FutureAwaits
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.transitmovements.config.AppConfig
import uk.gov.hmrc.transitmovements.it.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.Departure
import uk.gov.hmrc.transitmovements.models.DepartureId
import uk.gov.hmrc.transitmovements.models.DepartureWithoutMessages
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageType

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
  private val clockConfig            = app.injector.instanceOf[Clock]

  override lazy val repository = new DeparturesRepositoryImpl(appConfig, mongoComponent, clockConfig)

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

  "getDepartureMessageIds" should "return messageIds if there are messages" in {
    val messages =
      NonEmptyList
        .fromList(
          List(arbitraryMessage.arbitrary.sample.value, arbitraryMessage.arbitrary.sample.value, arbitraryMessage.arbitrary.sample.value)
            .sortBy(_.received)
            .reverse
        )
        .value
    val departure = arbitrary[Departure].sample.value.copy(messages = messages)

    await(repository.insert(departure).value)

    val result = await(repository.getDepartureMessageIds(departure.enrollmentEORINumber, departure._id, None).value)
    result.right.get.get should be(
      departure.messages.map(
        x => x.id
      )
    )
  }

  "getDepartureMessageIds" should "return messageIds if there are messages that were received since the given time" in {
    val dateTime = arbitrary[OffsetDateTime].sample.value

    val messages =
      NonEmptyList
        .fromList(
          List(
            arbitraryMessage.arbitrary.sample.value.copy(received = dateTime.plusMinutes(1)),
            arbitraryMessage.arbitrary.sample.value.copy(received = dateTime),
            arbitraryMessage.arbitrary.sample.value.copy(received = dateTime.minusMinutes(1))
          )
        )
        .value

    val departure = arbitrary[Departure].sample.value.copy(messages = messages)

    await(repository.insert(departure).value)

    val result = await(repository.getDepartureMessageIds(departure.enrollmentEORINumber, departure._id, Some(dateTime)).value)
    result.right.get.get should be(
      NonEmptyList.fromListUnsafe(
        departure.messages
          .take(2)
          .map(
            x => x.id
          )
      )
    )
  }

  "getDepartureMessageIds" should "return none if the departure doesn't exist" in {
    val result = await(repository.getDepartureMessageIds(EORINumber("ABC"), DepartureId("XYZ"), None).value)
    result.right.get.isEmpty should be(true)
  }

  "getDepartureIds" should
    "return a list of departure ids for the supplied EORI sorted by last updated, latest first" in {
      GetDeparturesSetup.setup()
      val result = await(repository.getDepartureIds(GetDeparturesSetup.eoriGB).value)

      result.right.get.value should be(NonEmptyList(DepartureId("10004"), List(DepartureId("10001"))))
    }

  it should "return no departure ids for an EORI that doesn't exist" in {
    GetDeparturesSetup.setup()
    val result = await(repository.getDepartureIds(EORINumber("FR999")).value)

    result.right.get should be(None)
  }

  it should "return no departure ids when the db is empty" in {
    // the collection is empty at this point due to DefaultPlayMongoRepositorySupport
    val result = await(repository.getDepartureIds(EORINumber("FR999")).value)
    result.right.get should be(None)
  }

  object GetDeparturesSetup {

    val eoriGB = EORINumber("GB00001")
    val eoriXI = EORINumber("XI00001")

    def setup() {

      val departure1 = Departure(
        _id = DepartureId("10001"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = EORINumber("20001"),
        movementReferenceNumber = None,
        created = instant,
        updated = instant,
        messages = NonEmptyList(
          Message(
            id = MessageId("00011"),
            received = instant,
            generated = instant,
            messageType = MessageType.DeclarationData,
            triggerId = None,
            url = None,
            body = None
          ),
          tail = List.empty
        )
      )

      val departure2 = departure1.copy(_id = DepartureId("10002"), enrollmentEORINumber = eoriXI, updated = instant.plusMinutes(1))
      val departure3 = departure1.copy(_id = DepartureId("10003"), enrollmentEORINumber = eoriXI, updated = instant.minusMinutes(3))
      val departure4 = departure1.copy(_id = DepartureId("10004"), enrollmentEORINumber = eoriGB, updated = instant.plusMinutes(1))

      //populate db in non-time order
      await(repository.insert(departure3).value)
      await(repository.insert(departure2).value)
      await(repository.insert(departure4).value)
      await(repository.insert(departure1).value)
    }

  }

  "updateMessages" should
    "add a message to the matching movement and set updated parameter" in {

      val message1 = arbitrary[Message].sample.value.copy(body = None, messageType = MessageType.DeclarationData, triggerId = None)

      val departureID = DepartureId("ABC")
      val departure =
        arbitrary[Departure].sample.value
          .copy(
            _id = departureID,
            created = instant,
            updated = instant,
            messages = NonEmptyList(message1, List.empty)
          )

      await(
        repository.insert(departure).value
      )

      val message2 =
        arbitrary[Message].sample.value.copy(body = None, messageType = MessageType.DepartureOfficeRejection, triggerId = Some(MessageId(departureID.value)))

      val result = await(
        repository.updateMessages(departureID, message2).value
      )

      result should be(Right(()))

      val movement = await {
        repository.collection.find(Filters.eq("_id", "ABC")).first().toFuture()
      }

      movement.updated shouldNot be(instant)
      movement.messages.length should be(2)
      movement.messages.toList should contain(message1)
      movement.messages.toList should contain(message2)

    }
}
