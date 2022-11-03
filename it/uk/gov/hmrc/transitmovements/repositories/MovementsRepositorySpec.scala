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
import uk.gov.hmrc.transitmovements.models.DepartureWithoutMessages
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.Movement
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.MovementType
import uk.gov.hmrc.transitmovements.models.MovementWithoutMessages
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse
import uk.gov.hmrc.transitmovements.services.errors.MongoError

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import scala.concurrent.ExecutionContext.Implicits.global

class MovementsRepositorySpec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with FutureAwaits
    with DefaultAwaitTimeout
    with Logging
    with DefaultPlayMongoRepositorySupport[Movement]
    with ModelGenerators
    with OptionValues {

  val instant: OffsetDateTime = OffsetDateTime.of(2022, 5, 25, 16, 0, 0, 0, ZoneOffset.UTC)

  override lazy val mongoComponent: MongoComponent = {
    val databaseName: String = "test-movements"
    val mongoUri: String     = s"mongodb://localhost:27017/$databaseName?retryWrites=false"
    MongoComponent(mongoUri)
  }

  implicit lazy val app: Application = GuiceApplicationBuilder().configure().build()
  private val appConfig              = app.injector.instanceOf[AppConfig]
  private val clockConfig            = app.injector.instanceOf[Clock]

  override lazy val repository = new MovementsRepositoryImpl(appConfig, mongoComponent, clockConfig)

  "DepartureMovementRepository" should "have the correct name" in {
    repository.collectionName shouldBe "movements"
  }

  "insert" should "add the given departure to the database" in {

    val departure = arbitrary[Movement].sample.value.copy(_id = MovementId("2"))
    await(
      repository.insert(departure).value
    )

    val firstItem = await {
      repository.collection.find(Filters.eq("_id", departure._id.value)).first().toFuture()
    }

    firstItem._id.value should be(departure._id.value)
  }

  "getDepartureWithoutMessages" should "return DepartureWithoutMessages if it exists" in {
    val departure = arbitrary[Movement].sample.value.copy(movementType = MovementType.Departure)

    await(repository.insert(departure).value)

    val result = await(repository.getMovementWithoutMessages(departure.enrollmentEORINumber, departure._id).value)
    result.right.get.get should be(DepartureWithoutMessages.fromDeparture(departure))
  }

  "getDepartureWithoutMessages" should "return none if the departure doesn't exist" in {
    val departure = arbitrary[Movement].sample.value.copy(_id = MovementId("1"))

    await(repository.insert(departure).value)

    val result = await(repository.getMovementWithoutMessages(departure.enrollmentEORINumber, MovementId("2")).value)
    result.right.get.isEmpty should be(true)
  }

  "getSingleMessage" should "return message response if it exists" in {
    val departure = arbitrary[Movement].sample.value

    await(repository.insert(departure).value)

    val result = await(repository.getSingleMessage(departure.enrollmentEORINumber, departure._id, departure.messages.head.id, departure.movementType).value)
    result.right.get.get should be(MessageResponse.fromMessageWithBody(departure.messages.head))
  }

  "getSingleMessage" should "return none if the message doesn't exist" in {
    val departure = arbitrary[Movement].sample.value

    await(repository.insert(departure).value)

    val result = await(repository.getSingleMessage(departure.enrollmentEORINumber, departure._id, MessageId("X"), departure.movementType).value)
    result.right.get.isEmpty should be(true)
  }

  "getMessages" should "return message responses if there are messages" in {
    val messages =
      NonEmptyList
        .fromList(
          List(arbitraryMessage.arbitrary.sample.value, arbitraryMessage.arbitrary.sample.value, arbitraryMessage.arbitrary.sample.value)
            .sortBy(_.received)
            .reverse
        )
        .value
    val departure = arbitrary[Movement].sample.value.copy(messages = messages)

    await(repository.insert(departure).value)

    val result = await(repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, None).value)
    result.right.get.get should be(
      departure.messages.map(
        message => MessageResponse.fromMessageWithoutBody(message)
      )
    )
  }

  "getMessages" should "return message responses if there are messages that were received since the given time" in {
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

    val departure = arbitrary[Movement].sample.value.copy(messages = messages)

    await(repository.insert(departure).value)

    val result = await(repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, Some(dateTime)).value)
    result.right.get.get should be(
      departure.messages
        .map(
          message => MessageResponse.fromMessageWithoutBody(message)
        )
    )
  }

  "getMessages" should "return none if the departure doesn't exist" in {
    val result = await(repository.getMessages(EORINumber("NONEXISTENT_EORI"), MovementId("NONEXISTENT_ID"), MovementType.Departure, None).value)
    result.right.get.isEmpty should be(true)
  }

  "getDepartures" should
    "return a list of departure responses for the supplied EORI sorted by last updated, latest first" in {
      GetMovementsSetup.setup()
      val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Departure).value)

      result.right.get.value should be(
        NonEmptyList(
          MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB2),
          List(MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB1))
        )
      )
    }

  it should "return no departure ids for an EORI that doesn't exist" in {
    GetMovementsSetup.setup()
    val result = await(repository.getMovements(EORINumber("FR999"), MovementType.Departure).value)

    result.right.get should be(None)
  }

  it should "return no departure ids when the db is empty" in {
    // the collection is empty at this point due to DefaultPlayMongoRepositorySupport
    val result = await(repository.getMovements(EORINumber("FR999"), MovementType.Departure).value)
    result.right.get should be(None)
  }

  "getArrivals" should
    "return a list of arrival responses for the supplied EORI sorted by last updated, latest first" in {
      GetMovementsSetup.setup()
      val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Arrival).value)

      result.right.get.value should be(
        NonEmptyList(
          MovementWithoutMessages.fromMovement(GetMovementsSetup.arrivalGB2),
          List(MovementWithoutMessages.fromMovement(GetMovementsSetup.arrivalGB1))
        )
      )
    }

  it should "return no arrival ids for an EORI that doesn't exist" in {
    GetMovementsSetup.setup()
    val result = await(repository.getMovements(EORINumber("FR999"), MovementType.Arrival).value)

    result.right.get should be(None)
  }

  it should "return no arrival ids when the db is empty" in {
    // the collection is empty at this point due to DefaultPlayMongoRepositorySupport
    val result = await(repository.getMovements(EORINumber("FR999"), MovementType.Arrival).value)
    result.right.get should be(None)
  }

  object GetMovementsSetup {

    val eoriGB  = arbitrary[EORINumber].sample.value
    val eoriXI  = arbitrary[EORINumber].sample.value
    val message = arbitrary[Message].sample.value

    val departureGB1 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementType = MovementType.Departure,
        created = instant,
        updated = instant,
        messages = NonEmptyList(message, List.empty)
      )

    val arrivalGB1 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementType = MovementType.Arrival,
        created = instant,
        updated = instant,
        messages = NonEmptyList(message, List.empty)
      )

    val mrnGen = arbitrary[MovementReferenceNumber]

    val departureXi1 =
      arbitrary[Movement].sample.value.copy(enrollmentEORINumber = eoriXI, updated = instant.plusMinutes(1), movementReferenceNumber = mrnGen.sample)

    val departureXi2 =
      arbitrary[Movement].sample.value.copy(enrollmentEORINumber = eoriXI, updated = instant.minusMinutes(3), movementReferenceNumber = mrnGen.sample)

    val departureGB2 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(1),
        movementReferenceNumber = mrnGen.sample
      )

    val arrivalGB2 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementType = MovementType.Arrival,
        updated = instant.plusMinutes(1),
        movementReferenceNumber = mrnGen.sample
      )

    def setup() {

      //populate db in non-time order
      await(repository.insert(departureXi2).value)
      await(repository.insert(departureGB2).value)
      await(repository.insert(departureXi1).value)
      await(repository.insert(departureGB1).value)

      await(repository.insert(arrivalGB2).value)
      await(repository.insert(arrivalGB1).value)
    }

  }

  "updateMessages" should "if not MrnAllocated, add a message to the matching movement and set updated parameter" in {

    val message1 = arbitrary[Message].sample.value.copy(body = None, messageType = MessageType.DeclarationData, triggerId = None)

    val departureID = arbitrary[MovementId].sample.value
    val departure =
      arbitrary[Movement].sample.value
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
      repository.updateMessages(departureID, message2, None).value
    )

    result should be(Right(()))

    val movement = await {
      repository.collection.find(Filters.eq("_id", departureID.value)).first().toFuture()
    }

    movement.updated shouldNot be(instant)
    movement.messages.length should be(2)
    movement.messages.toList should contain(message1)
    movement.messages.toList should contain(message2)

  }

  "updateMessages" should "if MrnAllocated, update MRN field when message type is MRNAllocated, as well as messages and updated field" in {

    val message1 = arbitrary[Message].sample.value.copy(body = None, messageType = MessageType.MrnAllocated, triggerId = None)

    val departureId = arbitrary[MovementId].sample.value
    val departure =
      arbitrary[Movement].sample.value
        .copy(
          _id = departureId,
          created = instant,
          updated = instant,
          movementReferenceNumber = None,
          messages = NonEmptyList(message1, List.empty)
        )

    await(
      repository.insert(departure).value
    )

    val message2 =
      arbitrary[Message].sample.value.copy(body = None, messageType = MessageType.MrnAllocated, triggerId = Some(MessageId(departureId.value)))

    val mrn = arbitrary[MovementReferenceNumber].sample.value
    val result = await(
      repository.updateMessages(departureId, message2, Some(mrn)).value
    )

    result should be(Right(()))

    val movement = await {
      repository.collection.find(Filters.eq("_id", departureId.value)).first().toFuture()
    }

    movement.updated shouldNot be(instant)
    movement.messages.length should be(2)
    movement.messages.toList should contain(message1)
    movement.messages.toList should contain(message2)
    movement.movementReferenceNumber should be(Some(mrn))

  }

  "updateMessages" should "return error if there is no matching movement with the given id" in {

    val movementId = arbitrary[MovementId].sample.value

    val message =
      arbitrary[Message].sample.value.copy(body = None, messageType = MessageType.DepartureOfficeRejection, triggerId = Some(MessageId(movementId.value)))

    val result = await(
      repository.updateMessages(movementId, message, Some(arbitrary[MovementReferenceNumber].sample.get)).value
    )

    result should be(Left(MongoError.DocumentNotFound(s"No movement found with the given id: ${movementId.value}")))

  }
}