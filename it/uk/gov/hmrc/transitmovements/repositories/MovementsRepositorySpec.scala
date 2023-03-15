/*
 * Copyright 2023 HM Revenue & Customs
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
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.OptionValues
import org.scalatest.stats
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
import uk.gov.hmrc.transitmovements.models._
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse
import uk.gov.hmrc.transitmovements.services.errors.MongoError

import java.net.URI
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

  val instant: OffsetDateTime         = OffsetDateTime.of(2022, 5, 25, 16, 0, 0, 0, ZoneOffset.UTC)
  val receivedInstant: OffsetDateTime = OffsetDateTime.of(2022, 6, 12, 0, 0, 0, 0, ZoneOffset.UTC)

  override lazy val mongoComponent: MongoComponent = {
    val databaseName: String = "test-movements"
    val mongoUri: String     = s"mongodb://localhost:27017/$databaseName?retryWrites=false"
    MongoComponent(mongoUri)
  }

  implicit lazy val app: Application = GuiceApplicationBuilder().configure().build()
  private val appConfig              = app.injector.instanceOf[AppConfig]

  override lazy val repository = new MovementsRepositoryImpl(appConfig, mongoComponent)

  "DepartureMovementRepository" should "have the correct name" in {
    repository.collectionName shouldBe "movements"
  }

  "insert" should "add the given movement to the database" in {

    val departure = arbitrary[Movement].sample.value.copy(_id = MovementId("2"))
    await(
      repository.insert(departure).value
    )

    val firstItem = await {
      repository.collection.find(Filters.eq("_id", departure._id.value)).first().toFuture()
    }

    firstItem._id.value should be(departure._id.value)
  }

  "insert" should "add an empty movement to the database" in {

    lazy val emptyMovement = arbitrary[Movement].sample.value.copy(
      _id = MovementId("2"),
      movementEORINumber = None,
      movementReferenceNumber = None,
      messages = Vector.empty[Message]
    )

    await(
      repository.insert(emptyMovement).value
    )

    val firstItem = await {
      repository.collection.find(Filters.eq("_id", emptyMovement._id.value)).first().toFuture()
    }

    firstItem._id.value should be(emptyMovement._id.value)
    firstItem.movementEORINumber should be(None)
    firstItem.messages.isEmpty should be(true)
  }

  "getMovementWithoutMessages" should "return MovementWithoutMessages if it exists" in {
    val movement = arbitrary[Movement].sample.value

    await(repository.insert(movement).value)

    val result = await(repository.getMovementWithoutMessages(movement.enrollmentEORINumber, movement._id, movement.movementType).value)
    result.toOption.get.get should be(MovementWithoutMessages.fromMovement(movement))
  }

  "getMovementWithoutMessages" should "return none if the movement doesn't exist" in {
    val movement = arbitrary[Movement].sample.value.copy(_id = MovementId("1"))

    await(repository.insert(movement).value)

    val result = await(repository.getMovementWithoutMessages(movement.enrollmentEORINumber, MovementId("2"), movement.movementType).value)

    result.toOption.get.isEmpty should be(true)
  }

  "getSingleMessage" should "return message response with uri if it exists" in {

    val message1 =
      arbitrary[Message].sample.value.copy(body = None, messageType = MessageType.DeclarationData, triggerId = None, status = Some(MessageStatus.Pending))

    val departure =
      arbitrary[Movement].sample.value
        .copy(
          created = instant,
          updated = instant,
          messages = Vector(message1)
        )

    await(repository.insert(departure).value)

    val result = await(repository.getSingleMessage(departure.enrollmentEORINumber, departure._id, departure.messages.head.id, departure.movementType).value)
    result.toOption.get.get should be(MessageResponse.fromMessageWithoutBody(departure.messages.head))
  }

  "getSingleMessage" should "return message response with Body if it exists" in {

    val message1 =
      arbitrary[Message].sample.value.copy(
        body = Some("body"),
        messageType = MessageType.DeclarationData,
        triggerId = None,
        status = Some(MessageStatus.Pending),
        uri = None
      )

    val departure =
      arbitrary[Movement].sample.value
        .copy(
          created = instant,
          updated = instant,
          messages = Vector(message1)
        )

    await(repository.insert(departure).value)

    val result = await(repository.getSingleMessage(departure.enrollmentEORINumber, departure._id, departure.messages.head.id, departure.movementType).value)
    result.toOption.get.get should be(MessageResponse.fromMessageWithBody(departure.messages.head))
  }

  "getSingleMessage" should "return none if the message doesn't exist" in {
    val departure = arbitrary[Movement].sample.value

    await(repository.insert(departure).value)

    val result = await(repository.getSingleMessage(departure.enrollmentEORINumber, departure._id, MessageId("X"), departure.movementType).value)
    result.toOption.get.isEmpty should be(true)
  }

  "getMessages" should "return message responses if there are messages" in {
    val messages =
      Vector(
        arbitraryMessage.arbitrary.sample.value.copy(uri = None),
        arbitraryMessage.arbitrary.sample.value.copy(uri = None),
        arbitraryMessage.arbitrary.sample.value.copy(uri = None)
      )
        .sortBy(_.received)
        .reverse

    val departure = arbitrary[Movement].sample.value.copy(messages = messages)

    await(repository.insert(departure).value)

    val result = await(repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, None).value)
    result.toOption.get should be(
      departure.messages.map(
        message => MessageResponse.fromMessageWithoutBody(message)
      )
    )
  }

  "getMessages" should "return message responses if there are messages that were received since the given time" in {
    val dateTime = arbitrary[OffsetDateTime].sample.value

    val messages =
      Vector(
        arbitraryMessage.arbitrary.sample.value.copy(received = dateTime.plusMinutes(1), uri = None),
        arbitraryMessage.arbitrary.sample.value.copy(received = dateTime, uri = None),
        arbitraryMessage.arbitrary.sample.value.copy(received = dateTime.minusMinutes(1), uri = None)
      )

    val departure = arbitrary[Movement].sample.value.copy(messages = messages)

    await(repository.insert(departure).value)

    val result = await(repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, Some(dateTime)).value)
    result.toOption.get should be(
      departure.messages
        .map(
          message => MessageResponse.fromMessageWithoutBody(message)
        )
    )
  }

  "getMessages" should "return none if the movement doesn't exist" in {
    val result = await(repository.getMessages(EORINumber("NONEXISTENT_EORI"), MovementId("NONEXISTENT_ID"), MovementType.Departure, None).value)
    result.toOption.get.isEmpty should be(true)
  }

  "getDepartures" should
    "return a list of departure movement responses for the supplied EORI sorted by last updated, latest first" in {
      GetMovementsSetup.setup()
      val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, None, None).value)

      result.toOption.get should be(
        Vector(
          MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB2),
          MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB1)
        )
      )
    }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that were updated since the given time" in {
    val dateTime = instant
    GetMovementsSetup.setup()
    await(repository.insert(GetMovementsSetup.departureGB3).value)
    val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, Some(dateTime), None).value)

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB2),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB1)
      )
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that matched with passed movementEORI" in {
    GetMovementsSetup.setup()
    await(repository.insert(GetMovementsSetup.departureGB4).value)
    val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, None, Some(GetMovementsSetup.movementEORI)).value)

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB2),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB1)
      )
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that were updated since the given time and passed movementEORI" in {
    val dateTime = instant
    GetMovementsSetup.setup()
    await(repository.insert(GetMovementsSetup.departureGB3).value)
    val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, Some(dateTime), Some(GetMovementsSetup.movementEORI)).value)

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB2),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB1)
      )
    )
  }

  it should "return no movement ids for an EORI that doesn't exist" in {
    GetMovementsSetup.setup()
    val result = await(repository.getMovements(EORINumber("FR999"), MovementType.Departure, None, None).value)

    result.toOption.get should be(Vector.empty[MovementWithoutMessages])
  }

  it should "return no movement ids when the db is empty" in {
    // the collection is empty at this point due to DefaultPlayMongoRepositorySupport
    val result = await(repository.getMovements(EORINumber("FR999"), MovementType.Departure, None, None).value)
    result.toOption.get should be(Vector.empty[MovementWithoutMessages])
  }

  "getArrivals" should
    "return a list of an arrival responses for the supplied EORI sorted by last updated, latest first" in {
      GetMovementsSetup.setup()
      val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Arrival, None, None).value)

      result.toOption.get should be(
        Vector(
          MovementWithoutMessages.fromMovement(GetMovementsSetup.arrivalGB2),
          MovementWithoutMessages.fromMovement(GetMovementsSetup.arrivalGB1)
        )
      )
    }

  it should "return a list of an arrival movement responses for the supplied EORI if there are movements that were updated since the given time" in {
    val dateTime = instant
    GetMovementsSetup.setup()
    await(repository.insert(GetMovementsSetup.arrivalGB3).value)
    val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Arrival, Some(dateTime), None).value)

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.arrivalGB2),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.arrivalGB1)
      )
    )
  }

  it should "return a list of an arrival movement responses for the supplied EORI if there are movements that matched with passed movementEORI" in {
    GetMovementsSetup.setup()
    await(repository.insert(GetMovementsSetup.arrivalGB4).value)
    val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Arrival, None, Some(GetMovementsSetup.movementEORI)).value)

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.arrivalGB2),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.arrivalGB1)
      )
    )
  }

  it should "return a list of an arrival movement responses for the supplied EORI if there are movements that were updated since the given time and passed movementEORI" in {
    val dateTime = instant
    GetMovementsSetup.setup()
    await(repository.insert(GetMovementsSetup.arrivalGB3).value)
    val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Arrival, Some(dateTime), Some(GetMovementsSetup.movementEORI)).value)

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.arrivalGB2),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.arrivalGB1)
      )
    )
  }

  it should "return no arrival ids for an EORI that doesn't exist" in {
    GetMovementsSetup.setup()
    val result = await(repository.getMovements(EORINumber("FR999"), MovementType.Arrival, None, None).value)

    result.toOption.get should be(Vector.empty[MovementWithoutMessages])
  }

  it should "return no arrival ids when the db is empty" in {
    // the collection is empty at this point due to DefaultPlayMongoRepositorySupport
    val result = await(repository.getMovements(EORINumber("FR999"), MovementType.Arrival, None, None).value)
    result.toOption.get should be(Vector.empty[MovementWithoutMessages])
  }

  object GetMovementsSetup {

    val eoriGB       = arbitrary[EORINumber].sample.value
    val eoriXI       = arbitrary[EORINumber].sample.value
    val movementEORI = arbitrary[EORINumber].sample.value
    val message      = arbitrary[Message].sample.value

    val departureGB1 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        created = instant,
        updated = instant,
        messages = Vector(message)
      )

    val arrivalGB1 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Arrival,
        created = instant,
        updated = instant,
        messages = Vector(message)
      )

    val mrnGen = arbitrary[MovementReferenceNumber]

    val departureXi1 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriXI,
        movementEORINumber = Some(movementEORI),
        updated = instant.plusMinutes(1),
        movementReferenceNumber = mrnGen.sample
      )

    val departureXi2 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriXI,
        movementEORINumber = Some(movementEORI),
        updated = instant.minusMinutes(3),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB2 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(1),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB3 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB4 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(EORINumber("1234AB")),
        movementType = MovementType.Departure,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = mrnGen.sample
      )

    val arrivalGB2 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Arrival,
        updated = instant.plusMinutes(1),
        movementReferenceNumber = mrnGen.sample
      )

    val arrivalGB3 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Arrival,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = mrnGen.sample
      )

    val arrivalGB4 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(EORINumber("1234AB")),
        movementType = MovementType.Arrival,
        updated = instant.minusMinutes(3),
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

  "updateMessages" should "if not MrnAllocated, add a message to the matching movement and set updated parameter to when the latest message was received" in {
    val message1 = arbitrary[Message].sample.value.copy(body = None, messageType = MessageType.DeclarationData, triggerId = None)

    val departureID = arbitrary[MovementId].sample.value
    val departure =
      arbitrary[Movement].sample.value
        .copy(
          _id = departureID,
          created = instant,
          updated = instant,
          messages = Vector(message1)
        )

    await(
      repository.insert(departure).value
    )

    val message2 =
      arbitrary[Message].sample.value.copy(
        body = None,
        messageType = MessageType.DepartureOfficeRejection,
        triggerId = Some(MessageId(departureID.value)),
        received = receivedInstant
      )

    val result = await(
      repository.updateMessages(departureID, message2, None, receivedInstant).value
    )

    result should be(Right(()))

    val movement = await {
      repository.collection.find(Filters.eq("_id", departureID.value)).first().toFuture()
    }

    movement.updated shouldEqual receivedInstant
    movement.messages.length should be(2)
    movement.messages.toList should contain(message1)
    movement.messages.toList should contain(message2)

  }

  "updateMessages" should "if MrnAllocated, update MRN field when message type is MRNAllocated, as well as messages and set updated parameter to when the latest message was received" in {

    val message1 = arbitrary[Message].sample.value.copy(body = None, messageType = MessageType.MrnAllocated, triggerId = None)

    val departureId = arbitrary[MovementId].sample.value
    val departure =
      arbitrary[Movement].sample.value
        .copy(
          _id = departureId,
          created = instant,
          updated = instant,
          movementReferenceNumber = None,
          messages = Vector(message1)
        )

    await(
      repository.insert(departure).value
    )

    val message2 =
      arbitrary[Message].sample.value.copy(
        body = None,
        messageType = MessageType.MrnAllocated,
        triggerId = Some(MessageId(departureId.value)),
        received = receivedInstant
      )

    val mrn = arbitrary[MovementReferenceNumber].sample.value
    val result = await(
      repository.updateMessages(departureId, message2, Some(mrn), receivedInstant).value
    )

    result should be(Right(()))

    val movement = await {
      repository.collection.find(Filters.eq("_id", departureId.value)).first().toFuture()
    }

    movement.updated shouldEqual receivedInstant
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
      repository.updateMessages(movementId, message, Some(arbitrary[MovementReferenceNumber].sample.get), instant).value
    )

    result should be(Left(MongoError.DocumentNotFound(s"No movement found with the given id: ${movementId.value}")))

  }

  "updateMessage" should "update the existing message with both status and object store url" in {

    val message1 =
      arbitrary[Message].sample.value.copy(body = None, messageType = MessageType.DeclarationData, triggerId = None, status = Some(MessageStatus.Pending))

    val departureMovement =
      arbitrary[Movement].sample.value
        .copy(
          created = instant,
          updated = instant,
          messages = Vector(message1)
        )

    await(
      repository.insert(departureMovement).value
    )

    val message2 = UpdateMessageMetadata(Some(ObjectStoreResourceLocation("some-url")), MessageStatus.Success)

    val result = await(
      repository.updateMessage(departureMovement._id, message1.id, message2, receivedInstant).value
    )

    result should be(Right(()))

    val movement = await {
      repository.collection.find(Filters.eq("_id", departureMovement._id.value)).first().toFuture()
    }

    movement.updated shouldEqual receivedInstant
    movement.messages.length should be(1)
    movement.messages.head.status shouldBe Some(message2.status)
    movement.messages.head.uri.value.toString shouldBe message2.objectStoreURI.get.value

  }

  "updateMessage" should "update an existing message with status only" in {
    val message1 =
      arbitrary[Message].sample.value.copy(body = None, messageType = MessageType.DeclarationData, triggerId = None, status = Some(MessageStatus.Pending))

    val departureMovement =
      arbitrary[Movement].sample.value
        .copy(
          created = instant,
          updated = instant,
          messages = Vector(message1)
        )

    await(
      repository.insert(departureMovement).value
    )

    val message2 = UpdateMessageMetadata(None, MessageStatus.Failed)

    val result = await(
      repository.updateMessage(departureMovement._id, message1.id, message2, receivedInstant).value
    )

    result should be(Right(()))

    val movement = await {
      repository.collection.find(Filters.eq("_id", departureMovement._id.value)).first().toFuture()
    }

    movement.updated shouldEqual receivedInstant
    movement.messages.length should be(1)
    movement.messages.head.status shouldBe Some(message2.status)

  }

  "updateMessage" should "return error if there is no matching movement with the given id" in {

    val movementId = arbitrary[MovementId].sample.value

    val message =
      arbitrary[Message].sample.value.copy(body = None, messageType = MessageType.DepartureOfficeRejection, triggerId = Some(MessageId(movementId.value)))

    val result = await(
      repository.updateMessage(movementId, message.id, UpdateMessageMetadata(None, MessageStatus.Failed), instant).value
    )

    result should be(Left(MongoError.DocumentNotFound(s"No movement found with the given id: ${movementId.value}")))

  }

}
