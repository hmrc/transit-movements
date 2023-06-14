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
import org.mongodb.scala.model.IndexModel
import org.mongodb.scala.model.IndexOptions
import org.mongodb.scala.model.Indexes
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
import uk.gov.hmrc.transitmovements.models._
import uk.gov.hmrc.transitmovements.models.formats.MongoFormats
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse
import uk.gov.hmrc.transitmovements.services.errors.MongoError

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
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

  "DepartureMovementRepository" should "have the correct column associated with the expected TTL" in {
    repository.indexes.head.getKeys shouldEqual Indexes.ascending("updated")

    repository.indexes.head.getOptions.getExpireAfter(TimeUnit.SECONDS) shouldEqual appConfig.documentTtl
  }

  "DepartureMovementRepository" should "have the correct domain format" in {
    repository.domainFormat shouldEqual MongoFormats.movementFormat
  }

  "DepartureMovementRepository" should "have the index for localReferenceNumber" in {
    repository.indexes
      .map(
        item => item.getKeys
      )
      .contains(Indexes.ascending("localReferenceNumber")) shouldBe true
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
    result.toOption.get should be(MovementWithoutMessages.fromMovement(movement))
  }

  "getMovementWithoutMessages" should "return none if the movement doesn't exist" in {
    val movement = arbitrary[Movement].sample.value.copy(_id = MovementId("1"))

    await(repository.insert(movement).value)

    val result = await(repository.getMovementWithoutMessages(movement.enrollmentEORINumber, MovementId("2"), movement.movementType).value)

    result.toOption.isEmpty should be(true)
  }

  "getSingleMessage" should "return message response with uri if it exists" in {

    val message1 =
      arbitrary[Message].sample.value.copy(body = None, messageType = Some(MessageType.DeclarationData), triggerId = None, status = Some(MessageStatus.Pending))

    val departure =
      arbitrary[Movement].sample.value
        .copy(
          created = instant,
          updated = instant,
          messages = Vector(message1)
        )

    await(repository.insert(departure).value)

    val result = await(repository.getSingleMessage(departure.enrollmentEORINumber, departure._id, departure.messages.head.id, departure.movementType).value)
    result.toOption.get should be(MessageResponse.fromMessageWithoutBody(departure.messages.head))
  }

  "getSingleMessage" should "return message response with Body if it exists" in {

    val message1 =
      arbitrary[Message].sample.value.copy(
        body = Some("body"),
        messageType = Some(MessageType.DeclarationData),
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
    result.toOption.get should be(MessageResponse.fromMessageWithBody(departure.messages.head))
  }

  "getSingleMessage" should "return none if the message doesn't exist" in {
    val departure = arbitrary[Movement].sample.value

    await(repository.insert(departure).value)

    val result = await(repository.getSingleMessage(departure.enrollmentEORINumber, departure._id, MessageId("X"), departure.movementType).value)
    result.toOption.isEmpty should be(true)
  }

  "getMessages" should "return message responses if there are messages" in {

    val dateTime = instant // mongo doesn't (generally) like arbitrary datetime values
    val messages = GetMovementsSetup
      .setupMessages(dateTime)
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
    val dateTime = instant // mongo doesn't (generally) like arbitrary datetime values

    val messages = GetMovementsSetup.setupMessages(dateTime)

    val departure = arbitrary[Movement].sample.value.copy(messages = messages)

    await(repository.insert(departure).value)

    val result = await(repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, Some(dateTime)).value)
    result.toOption.get should be(
      departure.messages
        .slice(0, 4)
        .map(
          message => MessageResponse.fromMessageWithoutBody(message)
        )
    )
  }

  "getMessages" should "return message responses if there are messages that were received up until the given time" in {
    val dateTime = instant

    val messages = GetMovementsSetup.setupMessages(dateTime)

    val departure = arbitrary[Movement].sample.value.copy(messages = messages)

    await(repository.insert(departure).value)

    val result = await(repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, None, None, None, Some(dateTime)).value)
    result.toOption.get should be(
      departure.messages
        .slice(3, 7)
        .map(
          message => MessageResponse.fromMessageWithoutBody(message)
        )
    )
  }

  "getMessages" should "return a list of message responses for the first page" in {
    val dateTime = instant

    val messages = GetMovementsSetup.setupMessages(dateTime)

    val departure = arbitrary[Movement].sample.value.copy(messages = messages)

    await(repository.insert(departure).value)

    val result = await(
      repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, None, None, Some(ItemCount(2)), None).value
    )
    result.toOption.get should be(
      departure.messages
        .slice(0, 2)
        .map(
          message => MessageResponse.fromMessageWithoutBody(message)
        )
    )
  }

  "getMessages" should "return a list of message responses for the second page" in {
    val dateTime = instant

    val messages = GetMovementsSetup.setupMessages(dateTime)

    val departure = arbitrary[Movement].sample.value.copy(messages = messages)

    await(repository.insert(departure).value)

    val result = await(
      repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, None, Some(PageNumber(2)), Some(ItemCount(2)), None).value
    )
    result.toOption.get should be(
      departure.messages
        .slice(2, 4)
        .map(
          message => MessageResponse.fromMessageWithoutBody(message)
        )
    )
  }

  "getMessages" should "return a list of message responses for the third page" in {
    val dateTime = instant

    val messages = GetMovementsSetup.setupMessages(dateTime)

    val departure = arbitrary[Movement].sample.value.copy(messages = messages)

    await(repository.insert(departure).value)

    val result = await(
      repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, None, Some(PageNumber(3)), Some(ItemCount(2)), None).value
    )
    result.toOption.get should be(
      departure.messages
        .slice(4, 6)
        .map(
          message => MessageResponse.fromMessageWithoutBody(message)
        )
    )
  }

  "getMessages" should "return a list of message responses for the last page" in {
    val dateTime = instant

    val messages = GetMovementsSetup.setupMessages(dateTime)

    val departure = arbitrary[Movement].sample.value.copy(messages = messages)

    await(repository.insert(departure).value)

    val result = await(
      repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, None, Some(PageNumber(4)), Some(ItemCount(2)), None).value
    )
    result.toOption.get should be(
      departure.messages
        .slice(6, 8)
        .map(
          message => MessageResponse.fromMessageWithoutBody(message)
        )
    )
  }

  "getMessages" should "return an empty list for an out of range page" in {
    val dateTime = instant

    val messages = GetMovementsSetup.setupMessages(dateTime)

    val departure = arbitrary[Movement].sample.value.copy(messages = messages)

    await(repository.insert(departure).value)

    val result = await(
      repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, None, Some(PageNumber(5)), Some(ItemCount(2)), None).value
    )
    result.toOption.get should be(Vector.empty)

  }

  "getMessages" should "return all message responses between received since and received until, if they are both supplied" in {
    val dateTime = instant

    val messages = GetMovementsSetup.setupMessages(dateTime)

    val departure = arbitrary[Movement].sample.value.copy(messages = messages)

    await(repository.insert(departure).value)

    val result =
      await(
        repository
          .getMessages(
            departure.enrollmentEORINumber,
            departure._id,
            departure.movementType,
            Some(dateTime.minusMinutes(2)),
            None,
            None,
            Some(dateTime.plusMinutes(2))
          )
          .value
      )
    result.toOption.get should be(
      departure.messages
        .slice(1, 6)
        .map(
          message => MessageResponse.fromMessageWithoutBody(message)
        )
    )
  }

  "getMessages" should "return no message responses if received since is after received until" in {
    val dateTime = instant

    val messages = GetMovementsSetup.setupMessages(dateTime)

    val departure = arbitrary[Movement].sample.value.copy(messages = messages)

    await(repository.insert(departure).value)

    val result =
      await(
        repository
          .getMessages(
            departure.enrollmentEORINumber,
            departure._id,
            departure.movementType,
            Some(dateTime.plusMinutes(2)),
            None,
            None,
            Some(dateTime.minusMinutes(2))
          )
          .value
      )
    result.toOption.get should be(Vector.empty)
  }

  "getMessages" should "return none if the movement doesn't exist" in {
    val result = await(repository.getMessages(EORINumber("NONEXISTENT_EORI"), MovementId("NONEXISTENT_ID"), MovementType.Departure, None).value)
    result.toOption.get.isEmpty should be(true)
  }

  "getDepartures" should
    "return a list of departure movement responses for the supplied EORI sorted by last updated, latest first" in {
      GetMovementsSetup.setup()
      val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, None, None, None).value)

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
    val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, Some(dateTime), None, None).value)

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB2),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB1)
      )
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that were received up until the given time" in {
    val dateTime = instant
    GetMovementsSetup.setup()
    await(repository.insert(GetMovementsSetup.departureGB3).value)
    val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, None, None, None, None, None, Some(dateTime)).value)

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB1),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB3)
      )
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements between the updated since and the received until times" in {
    val dateTime = instant
    GetMovementsSetup.setup()
    await(repository.insert(GetMovementsSetup.departureGB3).value)
    await(repository.insert(GetMovementsSetup.departureGB7).value)
    await(repository.insert(GetMovementsSetup.departureGB10).value)
    await(repository.insert(GetMovementsSetup.departureGB11).value)

    val result = await(
      repository
        .getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, Some(dateTime.minusMinutes(5)), None, None, None, None, Some(dateTime.plusMinutes(10)))
        .value
    )

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB10),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB2),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB1),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB3)
      )
    )
  }

  it should "return no movement responses for the supplied EORI, if updated since is after received until" in {
    val dateTime = instant
    GetMovementsSetup.setup()
    await(repository.insert(GetMovementsSetup.departureGB3).value)
    await(repository.insert(GetMovementsSetup.departureGB7).value)
    await(repository.insert(GetMovementsSetup.departureGB10).value)
    await(repository.insert(GetMovementsSetup.departureGB11).value)

    val result = await(
      repository
        .getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, Some(dateTime.plusMinutes(1)), None, None, None, None, Some(dateTime.minusMinutes(1)))
        .value
    )

    result.toOption.get should be(
      Vector.empty
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that matched with passed movementEORI" in {
    GetMovementsSetup.setup()
    await(repository.insert(GetMovementsSetup.departureGB4).value)
    val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, None, Some(GetMovementsSetup.movementEORI), None).value)

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB2),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB1)
      )
    )
  }

  it should "return a list of departure movement responses for the first page" in {

    GetMovementsSetup.setupPagination()
    val result =
      await(
        repository
          .getMovements(
            GetMovementsSetup.eoriGB,
            MovementType.Departure,
            None,
            Some(GetMovementsSetup.movementEORI),
            None,
            Some(PageNumber(1)),
            Some(ItemCount(4))
          )
          .value
      )

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB31),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB30),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB29),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB28)
      )
    )

  }

  it should "return a list of departure movement responses for the second page" in {
    GetMovementsSetup.setupPagination()

    val result =
      await(
        repository
          .getMovements(
            GetMovementsSetup.eoriGB,
            MovementType.Departure,
            None,
            Some(GetMovementsSetup.movementEORI),
            None,
            Some(PageNumber(2)),
            Some(ItemCount(4))
          )
          .value
      )
    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB27),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB26),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB25),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB24)
      )
    )

  }

  it should "return a list of departure movement responses for the third page" in {

    GetMovementsSetup.setupPagination()

    val result =
      await(
        repository
          .getMovements(
            GetMovementsSetup.eoriGB,
            MovementType.Departure,
            None,
            Some(GetMovementsSetup.movementEORI),
            None,
            Some(PageNumber(3)),
            Some(ItemCount(4))
          )
          .value
      )

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB23),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB22),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB21),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB20)
      )
    )

  }

  it should "return a list of departure movement responses for the last page" in {
    GetMovementsSetup.setupPagination()

    val result =
      await(
        repository
          .getMovements(
            GetMovementsSetup.eoriGB,
            MovementType.Departure,
            None,
            Some(GetMovementsSetup.movementEORI),
            None,
            Some(PageNumber(4)),
            Some(ItemCount(4))
          )
          .value
      )
    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB19)
      )
    )
  }

  it should "return an empty list for an out of range page" in {

    GetMovementsSetup.setupPagination()

    val result =
      await(
        repository
          .getMovements(
            GetMovementsSetup.eoriGB,
            MovementType.Departure,
            None,
            Some(GetMovementsSetup.movementEORI),
            None,
            Some(PageNumber(5)),
            Some(ItemCount(4))
          )
          .value
      )

    result.toOption.get should be(
      Vector()
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that were updated since the given time and passed movementEORI" in {
    val dateTime = instant
    GetMovementsSetup.setup()
    await(repository.insert(GetMovementsSetup.departureGB3).value)
    val result =
      await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, Some(dateTime), Some(GetMovementsSetup.movementEORI), None).value)

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB2),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB1)
      )
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that were received up until the given time and match the movementEORI" in {
    val dateTime = instant

    GetMovementsSetup.setup()
    await(repository.insert(GetMovementsSetup.departureGB3).value)
    await(repository.insert(GetMovementsSetup.departureGB10).value)
    val result =
      await(
        repository
          .getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, None, Some(GetMovementsSetup.movementEORI), None, None, None, Some(dateTime))
          .value
      )

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB1),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB3)
      )
    )
  }

  it should "return a list of departure movement responses for the supplied EORI since the beginning if the received until and updated since fields are entered and match the movementEORI" in {
    val dateTime = instant

    GetMovementsSetup.setup()
    await(repository.insert(GetMovementsSetup.departureGB3).value)
    await(repository.insert(GetMovementsSetup.departureGB10).value)
    val result =
      await(
        repository
          .getMovements(
            GetMovementsSetup.eoriGB,
            MovementType.Departure,
            Some(dateTime.minusMinutes(5)),
            Some(GetMovementsSetup.movementEORI),
            None,
            None,
            None,
            Some(dateTime.plusMinutes(11))
          )
          .value
      )

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB10),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB2),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB1),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB3)
      )
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that matched with passed MRN" in {
    await(repository.insert(GetMovementsSetup.departureGB4).value)
    val result =
      await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, None, None, GetMovementsSetup.departureGB4.movementReferenceNumber).value)

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB4)
      )
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that matched with partial match MRN" in {
    await(repository.insert(GetMovementsSetup.departureGB5).value)
    await(repository.insert(GetMovementsSetup.departureGB6).value)
    val result =
      await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, None, None, Some(MovementReferenceNumber("27WF9"))).value)

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB6),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.departureGB5)
      )
    )
  }

  it should "return no movement ids for an EORI that doesn't exist" in {
    GetMovementsSetup.setup()
    val result = await(repository.getMovements(EORINumber("FR999"), MovementType.Departure, None, None, None).value)

    result.toOption.get should be(Vector.empty[MovementWithoutMessages])
  }

  it should "return no movement ids when the db is empty" in {
    // the collection is empty at this point due to DefaultPlayMongoRepositorySupport
    val result = await(repository.getMovements(EORINumber("FR999"), MovementType.Departure, None, None, None).value)
    result.toOption.get should be(Vector.empty[MovementWithoutMessages])
  }

  it should "return no movement ids for an MRN that doesn't exist" in {
    GetMovementsSetup.setup()
    val result = await(repository.getMovements(EORINumber("FR999"), MovementType.Departure, None, None, Some(MovementReferenceNumber("invalid"))).value)

    result.toOption.get should be(Vector.empty[MovementWithoutMessages])
  }

  "getArrivals" should
    "return a list of an arrival responses for the supplied EORI sorted by last updated, latest first" in {
      GetMovementsSetup.setup()
      val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Arrival, None, None, None).value)

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
    val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Arrival, Some(dateTime), None, None).value)

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
    val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Arrival, None, Some(GetMovementsSetup.movementEORI), None).value)

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
    val result =
      await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Arrival, Some(dateTime), Some(GetMovementsSetup.movementEORI), None).value)

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.arrivalGB2),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.arrivalGB1)
      )
    )
  }

  it should "return a list of arrival movement responses for the supplied EORI if there are movements that matched with passed MRN" in {
    await(repository.insert(GetMovementsSetup.arrivalGB3).value)
    val result =
      await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Arrival, None, None, GetMovementsSetup.arrivalGB3.movementReferenceNumber).value)

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.arrivalGB3)
      )
    )
  }

  it should "return a list of arrival movement responses for the supplied EORI if there are movements that matched with partial match MRN" in {
    await(repository.insert(GetMovementsSetup.arrivalGB5).value)
    await(repository.insert(GetMovementsSetup.arrivalGB6).value)
    val result =
      await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Arrival, None, None, Some(MovementReferenceNumber("27WF9"))).value)

    result.toOption.get should be(
      Vector(
        MovementWithoutMessages.fromMovement(GetMovementsSetup.arrivalGB6),
        MovementWithoutMessages.fromMovement(GetMovementsSetup.arrivalGB5)
      )
    )
  }

  it should "return no arrival ids for an EORI that doesn't exist" in {
    GetMovementsSetup.setup()
    val result = await(repository.getMovements(EORINumber("FR999"), MovementType.Arrival, None, None, None).value)

    result.toOption.get should be(Vector.empty[MovementWithoutMessages])
  }

  it should "return no arrival ids when the db is empty" in {
    // the collection is empty at this point due to DefaultPlayMongoRepositorySupport
    val result = await(repository.getMovements(EORINumber("FR999"), MovementType.Arrival, None, None, None).value)
    result.toOption.get should be(Vector.empty[MovementWithoutMessages])
  }

  it should "return no movement ids for an MRN that doesn't exist" in {
    GetMovementsSetup.setup()
    val result = await(repository.getMovements(EORINumber("FR999"), MovementType.Arrival, None, None, Some(MovementReferenceNumber("invalid"))).value)

    result.toOption.get should be(Vector.empty[MovementWithoutMessages])
  }

  object GetMovementsSetup {

    val eoriGB       = arbitrary[EORINumber].sample.value
    val eoriXI       = arbitrary[EORINumber].sample.value
    val movementEORI = arbitrary[EORINumber].sample.value
    val message      = arbitrary[Message].sample.value
    val lrn          = arbitrary[LocalReferenceNumber].sample.value

    val departureGB1 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        created = instant,
        updated = instant,
        messages = Vector(message),
        localReferenceNumber = Some(lrn)
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
        movementReferenceNumber = mrnGen.sample,
        localReferenceNumber = Some(lrn)
      )

    val departureGB2 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(1),
        movementReferenceNumber = mrnGen.sample,
        localReferenceNumber = Some(lrn)
      )

    val departureGB3 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = mrnGen.sample,
        localReferenceNumber = Some(lrn)
      )

    val departureGB4 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(EORINumber("1234AB")),
        movementType = MovementType.Departure,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = mrnGen.sample,
        localReferenceNumber = Some(lrn)
      )

    val departureGB5 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(EORINumber("1234AB")),
        movementType = MovementType.Departure,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = Some(MovementReferenceNumber("27WF9X1FQ9RCKN0TM5"))
      )

    val departureGB6 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(EORINumber("1234AB")),
        movementType = MovementType.Departure,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = Some(MovementReferenceNumber("27wF9X1FQ9RCKN0TM3"))
      )

    val departureGB7 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(EORINumber("1234AB")),
        movementType = MovementType.Departure,
        updated = instant.minusMinutes(7),
        movementReferenceNumber = Some(MovementReferenceNumber("27wF9X1FQ9RCKN0TM3"))
      )

    val arrivalGB2 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Arrival,
        updated = instant.plusMinutes(1),
        movementReferenceNumber = mrnGen.sample,
        localReferenceNumber = None
      )

    val arrivalGB3 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Arrival,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = mrnGen.sample,
        localReferenceNumber = None
      )

    val arrivalGB4 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(EORINumber("1234AB")),
        movementType = MovementType.Arrival,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = mrnGen.sample,
        localReferenceNumber = None
      )

    val arrivalGB5 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Arrival,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = Some(MovementReferenceNumber("27WF9X1FQ9RCKN0TM5"))
      )

    val arrivalGB6 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Arrival,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = Some(MovementReferenceNumber("27wF9X1FQ9RCKN0TM6"))
      )

    // For Pagination

    val departureGB10 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("10"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(10),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB11 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("11"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(11),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB12 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("12"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(12),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB13 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("13"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(13),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB14 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("14"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(14),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB15 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("15"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(15),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB16 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("16"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(16),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB17 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("17"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(17),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB18 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("18"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(18),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB19 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("19"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(19),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB20 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("20"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(20),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB21 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("21"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(21),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB22 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("22"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(22),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB23 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("23"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(23),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB24 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("24"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(24),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB25 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("25"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(25),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB26 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("26"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(26),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB27 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("27"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(27),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB28 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("28"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(28),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB29 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("29"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(29),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB30 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("30"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(30),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB31 =
      arbitrary[Movement].sample.value.copy(
        _id = MovementId("31"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(31),
        movementReferenceNumber = mrnGen.sample
      )

    def setup() = {
      //populate db in non-time order
      await(repository.insert(departureXi2).value)
      await(repository.insert(departureGB2).value)
      await(repository.insert(departureXi1).value)
      await(repository.insert(departureGB1).value)

      await(repository.insert(arrivalGB2).value)
      await(repository.insert(arrivalGB1).value)
    }

    def setupMessages(dateTime: OffsetDateTime) =
      Vector(
        arbitraryMessage.arbitrary.sample.value.copy(received = dateTime.plusMinutes(3), uri = None),
        arbitraryMessage.arbitrary.sample.value.copy(received = dateTime.plusMinutes(2), uri = None),
        arbitraryMessage.arbitrary.sample.value.copy(received = dateTime.plusMinutes(1), uri = None),
        arbitraryMessage.arbitrary.sample.value.copy(received = dateTime, uri = None),
        arbitraryMessage.arbitrary.sample.value.copy(received = dateTime.minusMinutes(1), uri = None),
        arbitraryMessage.arbitrary.sample.value.copy(received = dateTime.minusMinutes(2), uri = None),
        arbitraryMessage.arbitrary.sample.value.copy(received = dateTime.minusMinutes(3), uri = None)
      )

    def setupPagination() = {
      // for each GBXX, the XX represents both the id and the plus minutes, so expected order can be determined in tests.
      // add various non-departure and non-GB movements; populate db in non-time order

      await(repository.insert(GetMovementsSetup.departureGB19).value)
      await(repository.insert(GetMovementsSetup.departureGB21).value)
      await(repository.insert(GetMovementsSetup.departureGB4).value)
      await(repository.insert(GetMovementsSetup.departureGB30).value)
      await(repository.insert(GetMovementsSetup.arrivalGB5).value)
      await(repository.insert(GetMovementsSetup.departureXi1).value)
      await(repository.insert(GetMovementsSetup.departureGB22).value)
      await(repository.insert(GetMovementsSetup.departureGB27).value)
      await(repository.insert(GetMovementsSetup.departureGB20).value)
      await(repository.insert(GetMovementsSetup.departureGB24).value)
      await(repository.insert(GetMovementsSetup.departureGB31).value)
      await(repository.insert(GetMovementsSetup.departureXi2).value)
      await(repository.insert(GetMovementsSetup.arrivalGB2).value)
      await(repository.insert(GetMovementsSetup.departureGB29).value)
      await(repository.insert(GetMovementsSetup.departureGB25).value)
      await(repository.insert(GetMovementsSetup.departureGB26).value)
      await(repository.insert(GetMovementsSetup.departureGB28).value)
      await(repository.insert(GetMovementsSetup.departureGB23).value)
    }

  }

  "updateMessages" should "if not MrnAllocated, add a message to the matching movement and set updated parameter to when the latest message was received" in {
    val message1 = arbitrary[Message].sample.value.copy(body = None, messageType = Some(MessageType.DeclarationData), triggerId = None)

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
        messageType = Some(MessageType.DepartureOfficeRejection),
        triggerId = Some(MessageId(departureID.value)),
        received = receivedInstant
      )

    val result = await(
      repository.attachMessage(departureID, message2, None, receivedInstant).value
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

    val message1 = arbitrary[Message].sample.value.copy(body = None, messageType = Some(MessageType.MrnAllocated), triggerId = None)

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
        messageType = Some(MessageType.MrnAllocated),
        triggerId = Some(MessageId(departureId.value)),
        received = receivedInstant
      )

    val mrn = arbitrary[MovementReferenceNumber].sample.value
    val result = await(
      repository.attachMessage(departureId, message2, Some(mrn), receivedInstant).value
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
      arbitrary[Message].sample.value.copy(body = None, messageType = Some(MessageType.DepartureOfficeRejection), triggerId = Some(MessageId(movementId.value)))

    val result = await(
      repository.attachMessage(movementId, message, Some(arbitrary[MovementReferenceNumber].sample.get), instant).value
    )

    result should be(Left(MongoError.DocumentNotFound(s"No movement found with the given id: ${movementId.value}")))

  }

  "updateMessage" should "update the existing message with both status and object store url" in {

    val message1 =
      arbitrary[Message].sample.value.copy(body = None, messageType = Some(MessageType.DeclarationData), triggerId = None, status = Some(MessageStatus.Pending))

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

    val message2 = UpdateMessageData(objectStoreURI = Some(ObjectStoreURI("common-transit-convention-traders/some-url.xml")), status = MessageStatus.Success)

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
      arbitrary[Message].sample.value.copy(body = None, messageType = Some(MessageType.DeclarationData), triggerId = None, status = Some(MessageStatus.Pending))

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

    val message2 = UpdateMessageData(status = MessageStatus.Failed)

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

  "updateMessage" should "update the existing message with both status and messageType" in {

    val message1 =
      arbitrary[Message].sample.value.copy(body = None, messageType = Some(MessageType.DeclarationData), triggerId = None, status = Some(MessageStatus.Pending))

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

    val message2 = UpdateMessageData(objectStoreURI = None, status = MessageStatus.Success, messageType = Some(MessageType.DeclarationAmendment))

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
    movement.messages.head.messageType.get.code shouldBe message2.messageType.get.code
  }

  "updateMessage" should "update the existing message with status, object store url, size, and messageType" in {

    val message1 =
      arbitrary[Message].sample.value.copy(body = None, messageType = Some(MessageType.DeclarationData), triggerId = None, status = Some(MessageStatus.Pending))

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

    val message2 = UpdateMessageData(
      objectStoreURI = Some(ObjectStoreURI("common-transit-convention-traders/some-url.xml")),
      status = MessageStatus.Success,
      size = Option(4L),
      messageType = Some(MessageType.DeclarationAmendment)
    )

    val result = await(
      repository.updateMessage(departureMovement._id, message1.id, message2, receivedInstant).value
    )

    result should be(Right(()))

    whenReady(
      repository.collection.find(Filters.eq("_id", departureMovement._id.value)).first().toFuture()
    ) {
      movement =>
        movement.updated shouldEqual receivedInstant
        movement.messages.length should be(1)

        val message = movement.messages.head
        message.status shouldBe Some(message2.status)
        message.uri.value.toString shouldBe message2.objectStoreURI.get.value
        message.messageType.get.code shouldBe message2.messageType.get.code
        message.size shouldBe Some(4L)
    }
  }

  "updateMessage" should "update the existing message with status, object store url, size, messageType and generationDate" in {

    val message1 =
      arbitrary[Message].sample.value.copy(body = None, messageType = Some(MessageType.DeclarationData), triggerId = None, status = Some(MessageStatus.Pending))

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

    val now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS)

    val message2 = UpdateMessageData(
      objectStoreURI = Some(ObjectStoreURI("common-transit-convention-traders/some-url.xml")),
      status = MessageStatus.Success,
      size = Option(4L),
      messageType = Some(MessageType.DeclarationAmendment),
      generationDate = Some(now)
    )

    val result = await(
      repository.updateMessage(departureMovement._id, message1.id, message2, receivedInstant).value
    )

    result should be(Right(()))

    whenReady(
      repository.collection.find(Filters.eq("_id", departureMovement._id.value)).first().toFuture()
    ) {
      movement =>
        movement.updated shouldEqual receivedInstant
        movement.messages.length should be(1)

        val message = movement.messages.head
        message.status shouldBe Some(message2.status)
        message.uri.value.toString shouldBe message2.objectStoreURI.get.value
        message.messageType.get.code shouldBe message2.messageType.get.code
        message.size shouldBe Some(4L)
        message.generated shouldBe Some(now)
    }
  }

  "updateMessage" should "return error if there is no matching movement with the given id" in {

    val movementId = arbitrary[MovementId].sample.value

    val message =
      arbitrary[Message].sample.value.copy(body = None, messageType = Some(MessageType.DepartureOfficeRejection), triggerId = Some(MessageId(movementId.value)))

    val result = await(
      repository.updateMessage(movementId, message.id, UpdateMessageData(status = MessageStatus.Failed), instant).value
    )

    result should be(Left(MongoError.DocumentNotFound(s"No movement found with the given id: ${movementId.value}")))

  }

  "updateMovement" should "return an error if there is no matching movement with the given ID" in forAll(
    arbitrary[MovementId],
    arbitrary[EORINumber],
    arbitrary[MovementReferenceNumber]
  ) {
    (movementId, eori, mrn) =>
      val result = await(
        repository.updateMovement(movementId, Some(eori), Some(mrn), None, instant).value
      )

      result should be(Left(MongoError.DocumentNotFound(s"No movement found with the given id: ${movementId.value}")))
  }

  "checkDuplicate" should "check the duplicate LRN" in {

    val eoriXI       = arbitrary[EORINumber].sample.value
    val movementEORI = arbitrary[EORINumber].sample.value
    val lrn          = arbitrary[LocalReferenceNumber].sample
    val mrnGen       = arbitrary[MovementReferenceNumber]

    val message1 =
      arbitrary[Message].sample.value.copy(body = None, messageType = Some(MessageType.DeclarationData), triggerId = None, status = Some(MessageStatus.Pending))

    val departureXi2 =
      arbitrary[Movement].sample.value.copy(
        enrollmentEORINumber = eoriXI,
        movementEORINumber = Some(movementEORI),
        updated = instant,
        movementReferenceNumber = mrnGen.sample,
        localReferenceNumber = lrn,
        messages = Vector(message1)
      )

    await(repository.insert(departureXi2).value)

    val alreadyExistResult = await(
      repository.restrictDuplicateLRN(lrn.value).value
    )

    alreadyExistResult should be(
      Left(MongoError.ConflictError(s"LRN ${lrn.value.value} has previously been used and cannot be reused", lrn.value))
    )

    val notExistLRN = LocalReferenceNumber("1234")

    val notExistResult = await(
      repository.restrictDuplicateLRN(notExistLRN).value
    )

    notExistResult should be(Right(()))

  }

  it should "update the EORI if it is provided" in {
    val departureMovement =
      arbitrary[Movement].sample.value
        .copy(
          created = instant,
          updated = instant,
          messages = Vector(),
          movementType = MovementType.Departure,
          movementEORINumber = None,
          movementReferenceNumber = None
        )

    await(
      repository.insert(departureMovement).value
    )

    val result = await(
      repository.updateMovement(departureMovement._id, Some(departureMovement.enrollmentEORINumber), None, None, receivedInstant).value
    )

    result should be(Right(()))

    val movement = await {
      repository.collection.find(Filters.eq("_id", departureMovement._id.value)).first().toFuture()
    }

    movement.updated shouldEqual receivedInstant
    movement.movementEORINumber shouldEqual Some(movement.enrollmentEORINumber)
    movement.movementReferenceNumber shouldEqual None
  }

  it should "update the MRN if only the MRN was supplied" in {
    val departureMovement =
      arbitrary[Movement].sample.value
        .copy(
          created = instant,
          updated = instant,
          messages = Vector(),
          movementEORINumber = None,
          movementReferenceNumber = None
        )

    val mrn = arbitrary[MovementReferenceNumber].sample.get

    await(
      repository.insert(departureMovement).value
    )

    val result = await(
      repository.updateMovement(departureMovement._id, None, Some(mrn), None, receivedInstant).value
    )

    result should be(Right(()))

    val movement = await {
      repository.collection.find(Filters.eq("_id", departureMovement._id.value)).first().toFuture()
    }

    movement.updated shouldEqual receivedInstant
    movement.movementEORINumber shouldEqual None
    movement.movementReferenceNumber shouldEqual Some(mrn)
  }

  it should "update the EORI and LRN if they are supplied" in {
    val departureMovement =
      arbitrary[Movement].sample.value
        .copy(
          created = instant,
          updated = instant,
          messages = Vector(),
          movementType = MovementType.Departure,
          movementEORINumber = None,
          movementReferenceNumber = None,
          localReferenceNumber = None
        )

    val lrn = arbitrary[LocalReferenceNumber].sample.get

    await(
      repository.insert(departureMovement).value
    )

    val result = await(
      repository
        .updateMovement(departureMovement._id, Some(departureMovement.enrollmentEORINumber), None, Some(lrn), receivedInstant)
        .value
    )

    result should be(Right(()))

    val movement = await {
      repository.collection.find(Filters.eq("_id", departureMovement._id.value)).first().toFuture()
    }

    movement.updated shouldEqual receivedInstant
    movement.movementEORINumber shouldEqual Some(movement.enrollmentEORINumber)
    movement.localReferenceNumber shouldEqual Some(lrn)
  }

  it should "update the EORI and MRN if they are both supplied" in {
    val departureMovement =
      arbitrary[Movement].sample.value
        .copy(
          created = instant,
          updated = instant,
          messages = Vector(),
          movementType = MovementType.Arrival,
          movementEORINumber = None,
          movementReferenceNumber = None
        )

    val mrn = arbitrary[MovementReferenceNumber].sample.get

    await(
      repository.insert(departureMovement).value
    )

    val result = await(
      repository.updateMovement(departureMovement._id, Some(departureMovement.enrollmentEORINumber), Some(mrn), None, receivedInstant).value
    )

    result should be(Right(()))

    val movement = await {
      repository.collection.find(Filters.eq("_id", departureMovement._id.value)).first().toFuture()
    }

    movement.updated shouldEqual receivedInstant
    movement.movementEORINumber shouldEqual Some(movement.enrollmentEORINumber)
    movement.movementReferenceNumber shouldEqual Some(mrn)
  }

  it should "not update anything if the EORI, MRN and LRN are not supplied" in {
    val departureMovement =
      arbitrary[Movement].sample.value
        .copy(
          created = instant,
          updated = instant,
          messages = Vector(),
          movementType = MovementType.Arrival,
          movementEORINumber = None,
          movementReferenceNumber = None,
          localReferenceNumber = None
        )

    await(
      repository.insert(departureMovement).value
    )

    val result = await(
      repository.updateMovement(departureMovement._id, None, None, None, receivedInstant).value
    )

    result should be(Right(()))

    val movement = await {
      repository.collection.find(Filters.eq("_id", departureMovement._id.value)).first().toFuture()
    }

    movement.updated shouldEqual instant
    movement.movementEORINumber shouldEqual None
    movement.movementReferenceNumber shouldEqual None
    movement.localReferenceNumber shouldEqual None
  }

}
