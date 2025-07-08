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

package test.uk.gov.hmrc.transitmovements.repositories

import cats.implicits.catsSyntaxOptionId
import org.mockito.Mockito
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Aggregates
import org.mongodb.scala.SingleObservableFuture
import org.mongodb.scala.model.Filters
import org.mongodb.scala.model.Indexes
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.Application
import play.api.Logging
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.DefaultAwaitTimeout
import play.api.test.FutureAwaits
import test.uk.gov.hmrc.transitmovements.it.generators.ModelGenerators
import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.crypto.SymmetricCryptoFactory
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.transitmovements.config.AppConfig
import uk.gov.hmrc.transitmovements.models._
import uk.gov.hmrc.transitmovements.models.formats.MongoFormats
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoMessageMetadata
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoMessageMetadataAndBody
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoMovementEori
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoMovementSummary
import uk.gov.hmrc.transitmovements.models.mongo.write.MongoMessage
import uk.gov.hmrc.transitmovements.models.mongo.write.MongoMessageUpdateData
import uk.gov.hmrc.transitmovements.models.mongo.write.MongoMovement
import uk.gov.hmrc.transitmovements.repositories.MovementsRepositoryImpl
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
    with DefaultPlayMongoRepositorySupport[MongoMovement]
    with ModelGenerators
    with OptionValues
    with GuiceOneAppPerSuite {

  val instant: OffsetDateTime         = OffsetDateTime.of(2022, 5, 25, 16, 0, 0, 0, ZoneOffset.UTC)
  val receivedInstant: OffsetDateTime = OffsetDateTime.of(2022, 6, 12, 0, 0, 0, 0, ZoneOffset.UTC)

  override lazy val mongoComponent: MongoComponent = {
    val databaseName: String = "test-movements"
    val mongoUri: String     = s"mongodb://localhost:27017/$databaseName?retryWrites=false"
    MongoComponent(mongoUri)
  }

  implicit override lazy val app: Application = GuiceApplicationBuilder()
    .configure(
      "encryption.key"           -> "7CYXDDh/UbNDY1UV8bkxvTzur3pCUzsAvMVH+HsRWbY=",
      "encryption.tolerant-read" -> false
    )
    .build()
  private val appConfig = Mockito.spy(app.injector.instanceOf[AppConfig])

  implicit val crypto: Encrypter & Decrypter = SymmetricCryptoFactory.aesGcmCrypto(appConfig.encryptionKey)
  val mongoFormats: MongoFormats             = new MongoFormats(appConfig)

  override val repository: MovementsRepositoryImpl = new MovementsRepositoryImpl(appConfig, mongoComponent, mongoFormats)

  // helper methods
  def expectedMessageMetadata(original: MongoMessage): MongoMessageMetadata =
    MongoMessageMetadata(
      original.id,
      original.received,
      original.messageType,
      original.status
    )

  def expectedMessageSummaryWithBody(original: MongoMessage): MongoMessageMetadataAndBody =
    MongoMessageMetadataAndBody(
      original.id,
      original.received,
      original.messageType,
      original.uri,
      original.body,
      original.triggerId,
      original.status
    )

  def expectedMovementSummary(original: MongoMovement): MongoMovementSummary =
    MongoMovementSummary(
      original._id,
      original.enrollmentEORINumber,
      original.movementEORINumber,
      original.movementReferenceNumber,
      original.localReferenceNumber,
      original.created,
      original.updated
    )

  def expectedMovementWithEori(original: MongoMovement): MongoMovementEori =
    MongoMovementEori(
      original._id,
      original.enrollmentEORINumber,
      original.clientId,
      original.isTransitional
    )

  "DepartureMovementRepository" should "have the correct name" in {
    repository.collectionName shouldBe "movements"
  }

  "DepartureMovementRepository" should "have the correct column associated with the expected TTL" in {
    repository.indexes.head.getKeys shouldEqual Indexes.ascending("updated")

    repository.indexes.head.getOptions.getExpireAfter(TimeUnit.SECONDS) shouldEqual appConfig.documentTtl
  }

  "DepartureMovementRepository" should "have the correct domain format" in {
    repository.domainFormat shouldEqual mongoFormats.movementFormat
  }

  "DepartureMovementRepository" should "have the index for localReferenceNumber" in {
    repository.indexes
      .map(
        item => item.getKeys
      )
      .contains(Indexes.ascending("localReferenceNumber")) shouldBe true
  }

  "insert" should "add the given movement to the database and encrypt the message body" in {

    val body      = Gen.stringOfN(20, Gen.alphaNumChar).sample.get
    val message   = arbitrary[MongoMessage].sample.get.copy(body = Some(SensitiveString(body)))
    val departure = arbitrary[MongoMovement].sample.value.copy(_id = MovementId("2"), messages = Vector(message))
    await(
      repository.collection.insertOne(departure).toFuture()
    )

    val firstItem: MongoMovement = await {
      repository.collection.find(Filters.eq("_id", departure._id.value)).first().toFuture()
    }

    firstItem._id.value should be(departure._id.value)

    // now get the body, but get the raw string rather than the model.
    import com.mongodb.client.model.Filters.{eq => mEq}

    val findResult = repository.collection
      .aggregate[BsonDocument](
        Seq(
          Aggregates.filter(mEq("_id", departure._id.value)),
          Aggregates.unwind("$messages"),
          Aggregates.limit(1),
          Aggregates.project(BsonDocument("_id" -> 0, "body" -> "$messages.body"))
        )
      )
      .first()
      .toFuture()

    whenReady(findResult) {
      result =>
        // If unencrypted this will fail.
        result.getString("body").getValue should not be body
    }
  }

  "insert" should "add an empty movement to the database" in {

    lazy val emptyMovement = arbitrary[MongoMovement].sample.value.copy(
      _id = MovementId("2"),
      movementEORINumber = None,
      movementReferenceNumber = None,
      messages = Vector.empty[MongoMessage]
    )

    await(
      repository.collection.insertOne(emptyMovement).toFuture()
    )

    val firstItem = await {
      repository.collection.find(Filters.eq("_id", emptyMovement._id.value)).first().toFuture()
    }

    firstItem._id.value should be(emptyMovement._id.value)
    firstItem.movementEORINumber should be(None)
    firstItem.messages.isEmpty should be(true)
    firstItem.isTransitional should be(emptyMovement.isTransitional)
  }

  "getMovementWithoutMessages" should "return MovementWithoutMessages if it exists" in {
    val movement = arbitrary[MongoMovement].sample.value

    await(repository.collection.insertOne(movement).toFuture())

    val result = await(repository.getMovementWithoutMessages(movement.enrollmentEORINumber, movement._id, movement.movementType).value)
    result.toOption.get should be(expectedMovementSummary(movement))
  }

  "getMovementWithoutMessages" should "return MovementWithoutMessages if it exists with the 'isTransitional' flag not populated" in {
    val movement = arbitrary[MongoMovement].sample.value.copy(isTransitional = None)
    await(repository.collection.insertOne(movement).toFuture())

    val result = await(repository.getMovementWithoutMessages(movement.enrollmentEORINumber, movement._id, movement.movementType).value)
    result.toOption.get shouldBe expectedMovementSummary(movement)
  }

  "getMovementWithoutMessages" should "return none if the movement doesn't exist" in {
    val movement = arbitrary[MongoMovement].sample.value.copy(_id = MovementId("1"))

    await(repository.collection.insertOne(movement).toFuture())

    val result = await(repository.getMovementWithoutMessages(movement.enrollmentEORINumber, MovementId("2"), movement.movementType).value)

    result.toOption.isEmpty should be(true)
  }

  "getMovementEori" should "return MovementWithEori if it exists" in {
    val movement = arbitrary[MongoMovement].sample.value

    await(repository.collection.insertOne(movement).toFuture())

    val result = await(repository.getMovementEori(movement._id).value)
    result.toOption.get should be(expectedMovementWithEori(movement))
  }

  "getMovementEori" should "return MovementWithEori if it exists with the 'isTransitional' flag not populated" in {
    val movement = arbitrary[MongoMovement].sample.value.copy(isTransitional = None)

    await(repository.collection.insertOne(movement).toFuture())

    val result = await(repository.getMovementEori(movement._id).value)
    result.toOption.get should be(expectedMovementWithEori(movement.copy(isTransitional = true.some)))
  }

  "getMovementEori" should "return none if the movement doesn't exist" in {
    val movement = arbitrary[MongoMovement].sample.value.copy(_id = MovementId("1"))

    await(repository.collection.insertOne(movement).toFuture())

    val result = await(repository.getMovementEori(MovementId("2")).value)

    result.toOption.isEmpty should be(true)
  }

  "getSingleMessage" should "return message response with uri if it exists" in {

    val message1 =
      arbitrary[MongoMessage].sample.value.copy(
        body = None,
        messageType = Some(MessageType.DeclarationData),
        triggerId = None,
        status = Some(MessageStatus.Pending)
      )

    val departure =
      arbitrary[MongoMovement].sample.value
        .copy(
          created = instant,
          updated = instant,
          messages = Vector(message1)
        )

    await(repository.collection.insertOne(departure).toFuture())

    val result = await(repository.getSingleMessage(departure.enrollmentEORINumber, departure._id, message1.id, departure.movementType).value)
    result.toOption.get should be(expectedMessageSummaryWithBody(message1))
  }

  "getSingleMessage" should "return message response with Body if it exists" in {

    val message1 =
      arbitrary[MongoMessage].sample.value.copy(
        body = Some(SensitiveString("body")),
        messageType = Some(MessageType.DeclarationData),
        triggerId = None,
        status = Some(MessageStatus.Pending),
        uri = None
      )

    val departure =
      arbitrary[MongoMovement].sample.value
        .copy(
          created = instant,
          updated = instant,
          messages = Vector(message1)
        )

    await(repository.collection.insertOne(departure).toFuture())

    val result = await(repository.getSingleMessage(departure.enrollmentEORINumber, departure._id, departure.messages.head.id, departure.movementType).value)
    result.toOption.get should be(expectedMessageSummaryWithBody(departure.messages.head))
  }

  "getSingleMessage" should "return none if the message doesn't exist" in {
    val departure = arbitrary[MongoMovement].sample.value

    await(repository.collection.insertOne(departure).toFuture())

    val result = await(repository.getSingleMessage(departure.enrollmentEORINumber, departure._id, MessageId("X"), departure.movementType).value)
    result.toOption.isEmpty should be(true)
  }

  "getMessages" should "return message responses if there are messages" in {

    val dateTime = instant // mongo doesn't (generally) like arbitrary datetime values
    val messages = GetMovementsSetup
      .setupMessagesWithOutBody(dateTime)
      .sortBy(_.received)
      .reverse

    val departure = arbitrary[MongoMovement].sample.value.copy(messages = messages)

    await(repository.collection.insertOne(departure).toFuture())

    val result = await(repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, None).value)

    val paginationMessageSummary = result.toOption.get

    paginationMessageSummary.totalCount should be(TotalCount(7))

    paginationMessageSummary.messageSummary should be(
      departure.messages.map(expectedMessageMetadata)
    )
  }

  "getMessages" should "return message responses if there are messages that were received since the given time" in {
    val dateTime = instant // mongo doesn't (generally) like arbitrary datetime values

    val messages = GetMovementsSetup.setupMessagesWithOutBody(dateTime)

    val departure = arbitrary[MongoMovement].sample.value.copy(messages = messages)

    await(repository.collection.insertOne(departure).toFuture())

    val result = await(repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, Some(dateTime)).value)

    val paginationMessageSummary = result.toOption.get

    paginationMessageSummary.totalCount should be(TotalCount(4))

    paginationMessageSummary.messageSummary should be(
      departure.messages
        .slice(0, 4)
        .map(expectedMessageMetadata)
    )
  }

  "getMessages" should "return message responses if there are messages that were received up until the given time" in {
    val dateTime = instant

    val messages = GetMovementsSetup.setupMessagesWithOutBody(dateTime)

    val departure = arbitrary[MongoMovement].sample.value.copy(messages = messages)

    await(repository.collection.insertOne(departure).toFuture())

    val result = await(repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, None, None, None, Some(dateTime)).value)

    val paginationMessageSummary = result.toOption.get

    paginationMessageSummary.totalCount should be(TotalCount(4))

    paginationMessageSummary.messageSummary should be(
      departure.messages
        .slice(3, 7)
        .map(expectedMessageMetadata)
    )
  }

  "getMessages" should "return a list of message responses for the first page" in {
    val dateTime = instant

    val messages = GetMovementsSetup.setupMessagesWithOutBody(dateTime)

    val departure = arbitrary[MongoMovement].sample.value.copy(messages = messages)

    await(repository.collection.insertOne(departure).toFuture())

    val result = await(
      repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, None, None, Some(ItemCount(2)), None).value
    )

    val paginationMessageSummary = result.toOption.get

    paginationMessageSummary.totalCount should be(TotalCount(7))

    paginationMessageSummary.messageSummary should be(
      departure.messages
        .slice(0, 2)
        .map(expectedMessageMetadata)
    )
  }

  "getMessages" should "return a list of message responses for the second page" in {
    val dateTime = instant

    val messages = GetMovementsSetup.setupMessagesWithOutBody(dateTime)

    val departure = arbitrary[MongoMovement].sample.value.copy(messages = messages)

    await(repository.collection.insertOne(departure).toFuture())

    val result = await(
      repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, None, Some(PageNumber(2)), Some(ItemCount(2)), None).value
    )

    val paginationMessageSummary = result.toOption.get

    paginationMessageSummary.totalCount should be(TotalCount(7))

    paginationMessageSummary.messageSummary should be(
      departure.messages
        .slice(2, 4)
        .map(expectedMessageMetadata)
    )
  }

  "getMessages" should "return a list of message responses for the third page" in {
    val dateTime = instant

    val messages = GetMovementsSetup.setupMessagesWithOutBody(dateTime)

    val departure = arbitrary[MongoMovement].sample.value.copy(messages = messages)

    await(repository.collection.insertOne(departure).toFuture())

    val result = await(
      repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, None, Some(PageNumber(3)), Some(ItemCount(2)), None).value
    )

    val paginationMessageSummary = result.toOption.get

    paginationMessageSummary.totalCount should be(TotalCount(7))

    paginationMessageSummary.messageSummary should be(
      departure.messages
        .slice(4, 6)
        .map(expectedMessageMetadata)
    )
  }

  "getMessages" should "return a list of message responses for the last page" in {
    val dateTime = instant

    val messages = GetMovementsSetup.setupMessagesWithOutBody(dateTime)

    val departure = arbitrary[MongoMovement].sample.value.copy(messages = messages)

    await(repository.collection.insertOne(departure).toFuture())

    val result = await(
      repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, None, Some(PageNumber(4)), Some(ItemCount(2)), None).value
    )

    val paginationMessageSummary = result.toOption.get

    paginationMessageSummary.totalCount should be(TotalCount(7))

    paginationMessageSummary.messageSummary should be(
      departure.messages
        .slice(6, 8)
        .map(expectedMessageMetadata)
    )
  }

  "getMessages" should "return an empty list for an out of range page" in {
    val dateTime = instant

    val messages = GetMovementsSetup.setupMessages(dateTime)

    val departure = arbitrary[MongoMovement].sample.value.copy(messages = messages)

    await(repository.collection.insertOne(departure).toFuture())

    val result = await(
      repository.getMessages(departure.enrollmentEORINumber, departure._id, departure.movementType, None, Some(PageNumber(5)), Some(ItemCount(2)), None).value
    )

    val paginationMessageSummary = result.toOption.get

    paginationMessageSummary.totalCount should be(TotalCount(7))

    paginationMessageSummary.messageSummary should be(Vector.empty)

  }

  "getMessages" should "return all message responses between received since and received until, if they are both supplied" in {
    val dateTime = instant

    val messages = GetMovementsSetup.setupMessagesWithOutBody(dateTime)

    val departure = arbitrary[MongoMovement].sample.value.copy(messages = messages)

    await(repository.collection.insertOne(departure).toFuture())

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

    val paginationMessageSummary = result.toOption.get

    paginationMessageSummary.totalCount should be(TotalCount(5))

    paginationMessageSummary.messageSummary should be(
      departure.messages
        .slice(1, 6)
        .map(expectedMessageMetadata)
    )
  }

  "getMessages" should "return no message responses if received since is after received until" in {
    val dateTime = instant

    val messages = GetMovementsSetup.setupMessages(dateTime)

    val departure = arbitrary[MongoMovement].sample.value.copy(messages = messages)

    await(repository.collection.insertOne(departure).toFuture())

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

    val paginationMessageSummary = result.toOption.get

    paginationMessageSummary.totalCount should be(TotalCount(0))

    paginationMessageSummary.messageSummary should be(Vector.empty)
  }

  "getMessages" should "return none if the movement doesn't exist" in {
    val result = await(repository.getMessages(EORINumber("NONEXISTENT_EORI"), MovementId("NONEXISTENT_ID"), MovementType.Departure, None).value)
    result.toOption.get.totalCount should be(TotalCount(0))
  }

  "getMessageIdsAndType" should "return messages id, type if there are matching messages" in {

    val dateTime = instant
    val messages = GetMovementsSetup
      .setupMessagesWithOutBody(dateTime)
      .sortBy(_.received)
      .reverse

    val departure = arbitrary[MongoMovement].sample.value.copy(messages = messages)

    await(repository.collection.insertOne(departure).toFuture())

    val result = await(repository.getMessageIdsAndType(departure._id).value)

    val messageIdsAndType = result.toOption.get

    messageIdsAndType should be(
      departure.messages.map(expectedMessageMetadata)
    )
  }

  "getMessageIdsAndType" should "return error if there is no matching movement with the given id" in {

    val movementId = arbitrary[MovementId].sample.value

    val result = await(
      repository.getMessageIdsAndType(movementId).value
    )

    result should be(Left(MongoError.DocumentNotFound(s"No movement found with the given id: ${movementId.value}")))

  }

  "getMovements (Departures)" should
    "return a list of departure movement responses for the supplied EORI sorted by last updated, latest first" in {
      GetMovementsSetup.setup()
      val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, None, None, None, None, None, None, None).value)

      val paginationMovementSummary = result.toOption.get

      paginationMovementSummary.totalCount should be(TotalCount(2))

      paginationMovementSummary.movementSummary should be(
        Vector(
          expectedMovementSummary(GetMovementsSetup.departureGB2),
          expectedMovementSummary(GetMovementsSetup.departureGB1)
        )
      )
    }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that were updated since the given time" in {
    val dateTime = instant
    GetMovementsSetup.setup()
    await(repository.collection.insertOne(GetMovementsSetup.departureGB3).toFuture())
    val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, Some(dateTime), None, None, None, None, None, None).value)

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(2))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.departureGB2),
        expectedMovementSummary(GetMovementsSetup.departureGB1)
      )
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that were received up until the given time" in {
    val dateTime = instant
    GetMovementsSetup.setup()
    await(repository.collection.insertOne(GetMovementsSetup.departureGB3).toFuture())
    val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, None, None, None, None, None, Some(dateTime), None).value)

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(2))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.departureGB1),
        expectedMovementSummary(GetMovementsSetup.departureGB3)
      )
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements between the updated since and the received until times" in {
    val dateTime = instant
    GetMovementsSetup.setup()
    await(repository.collection.insertOne(GetMovementsSetup.departureGB3).toFuture())
    await(repository.collection.insertOne(GetMovementsSetup.departureGB7).toFuture())
    await(repository.collection.insertOne(GetMovementsSetup.departureGB10).toFuture())
    await(repository.collection.insertOne(GetMovementsSetup.departureGB11).toFuture())

    val result = await(
      repository
        .getMovements(
          GetMovementsSetup.eoriGB,
          MovementType.Departure,
          Some(dateTime.minusMinutes(5)),
          None,
          None,
          None,
          None,
          Some(dateTime.plusMinutes(10)),
          None
        )
        .value
    )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(4))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.departureGB10),
        expectedMovementSummary(GetMovementsSetup.departureGB2),
        expectedMovementSummary(GetMovementsSetup.departureGB1),
        expectedMovementSummary(GetMovementsSetup.departureGB3)
      )
    )
  }

  it should "return no movement responses for the supplied EORI, if updated since is after received until" in {
    val dateTime = instant
    GetMovementsSetup.setup()
    await(repository.collection.insertOne(GetMovementsSetup.departureGB3).toFuture())
    await(repository.collection.insertOne(GetMovementsSetup.departureGB7).toFuture())
    await(repository.collection.insertOne(GetMovementsSetup.departureGB10).toFuture())
    await(repository.collection.insertOne(GetMovementsSetup.departureGB11).toFuture())

    val result = await(
      repository
        .getMovements(
          GetMovementsSetup.eoriGB,
          MovementType.Departure,
          Some(dateTime.plusMinutes(1)),
          None,
          None,
          None,
          None,
          Some(dateTime.minusMinutes(1)),
          None
        )
        .value
    )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(0))

    paginationMovementSummary.movementSummary should be(
      Vector.empty
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that matched with passed movementEORI" in {
    GetMovementsSetup.setup()
    await(repository.collection.insertOne(GetMovementsSetup.departureGB4).toFuture())
    val result = await(
      repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, None, Some(GetMovementsSetup.movementEORI), None, None, None, None, None).value
    )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(2))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.departureGB2),
        expectedMovementSummary(GetMovementsSetup.departureGB1)
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
            Some(ItemCount(4)),
            None,
            None
          )
          .value
      )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(13))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.departureGB31),
        expectedMovementSummary(GetMovementsSetup.departureGB30),
        expectedMovementSummary(GetMovementsSetup.departureGB29),
        expectedMovementSummary(GetMovementsSetup.departureGB28)
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
            Some(ItemCount(4)),
            None,
            None
          )
          .value
      )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(13))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.departureGB27),
        expectedMovementSummary(GetMovementsSetup.departureGB26),
        expectedMovementSummary(GetMovementsSetup.departureGB25),
        expectedMovementSummary(GetMovementsSetup.departureGB24)
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
            Some(ItemCount(4)),
            None,
            None
          )
          .value
      )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(13))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.departureGB23),
        expectedMovementSummary(GetMovementsSetup.departureGB22),
        expectedMovementSummary(GetMovementsSetup.departureGB21),
        expectedMovementSummary(GetMovementsSetup.departureGB20)
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
            Some(ItemCount(4)),
            None,
            None
          )
          .value
      )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(13))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.departureGB19)
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
            Some(ItemCount(4)),
            None,
            None
          )
          .value
      )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(13))

    paginationMovementSummary.movementSummary should be(
      Vector()
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that were updated since the given time and passed movementEORI" in {
    val dateTime = instant
    GetMovementsSetup.setup()
    await(repository.collection.insertOne(GetMovementsSetup.departureGB3).toFuture())
    val result =
      await(
        repository
          .getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, Some(dateTime), Some(GetMovementsSetup.movementEORI), None, None, None, None, None)
          .value
      )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(2))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.departureGB2),
        expectedMovementSummary(GetMovementsSetup.departureGB1)
      )
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that were received up until the given time and match the movementEORI" in {
    val dateTime = instant

    GetMovementsSetup.setup()
    await(repository.collection.insertOne(GetMovementsSetup.departureGB3).toFuture())
    await(repository.collection.insertOne(GetMovementsSetup.departureGB10).toFuture())
    val result =
      await(
        repository
          .getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, None, Some(GetMovementsSetup.movementEORI), None, None, None, Some(dateTime), None)
          .value
      )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(2))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.departureGB1),
        expectedMovementSummary(GetMovementsSetup.departureGB3)
      )
    )
  }

  it should "return a list of departure movement responses for the supplied EORI since the beginning if the received until and updated since fields are entered and match the movementEORI" in {
    val dateTime = instant

    GetMovementsSetup.setup()
    await(repository.collection.insertOne(GetMovementsSetup.departureGB3).toFuture())
    await(repository.collection.insertOne(GetMovementsSetup.departureGB10).toFuture())
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
            Some(dateTime.plusMinutes(11)),
            None
          )
          .value
      )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(4))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.departureGB10),
        expectedMovementSummary(GetMovementsSetup.departureGB2),
        expectedMovementSummary(GetMovementsSetup.departureGB1),
        expectedMovementSummary(GetMovementsSetup.departureGB3)
      )
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that matched with passed MRN" in {
    await(repository.collection.insertOne(GetMovementsSetup.departureGB4).toFuture())
    val result =
      await(
        repository
          .getMovements(
            GetMovementsSetup.eoriGB,
            MovementType.Departure,
            None,
            None,
            GetMovementsSetup.departureGB4.movementReferenceNumber,
            None,
            None,
            None,
            None
          )
          .value
      )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(1))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.departureGB4)
      )
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that matched with partial match MRN" in {
    await(repository.collection.insertOne(GetMovementsSetup.departureGB5).toFuture())
    await(repository.collection.insertOne(GetMovementsSetup.departureGB6).toFuture())
    val result =
      await(
        repository
          .getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, None, None, Some(MovementReferenceNumber("27WF9")), None, None, None, None)
          .value
      )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(2))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.departureGB6),
        expectedMovementSummary(GetMovementsSetup.departureGB5)
      )
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that matched with passed LRN" in {
    await(repository.collection.insertOne(GetMovementsSetup.departureGB4).toFuture())
    val result =
      await(
        repository
          .getMovements(
            GetMovementsSetup.eoriGB,
            MovementType.Departure,
            None,
            None,
            None,
            localReferenceNumber = GetMovementsSetup.departureGB4.localReferenceNumber
          )
          .value
      )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(1))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.departureGB4)
      )
    )
  }

  it should "return a list of departure movement responses for the supplied EORI if there are movements that matched with partial match LRN" in {
    await(repository.collection.insertOne(GetMovementsSetup.departureGB5).toFuture())
    await(repository.collection.insertOne(GetMovementsSetup.departureGB6).toFuture())
    val result =
      await(
        repository
          .getMovements(GetMovementsSetup.eoriGB, MovementType.Departure, None, None, None, localReferenceNumber = Some(LocalReferenceNumber("3CnsT")))
          .value
      )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(2))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.departureGB6),
        expectedMovementSummary(GetMovementsSetup.departureGB5)
      )
    )
  }

  it should "return no movement ids for an EORI that doesn't exist" in {
    GetMovementsSetup.setup()
    val result = await(repository.getMovements(EORINumber("FR999"), MovementType.Departure, None, None, None, None, None, None, None).value)

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(0))
    paginationMovementSummary.movementSummary should be(Vector.empty[MovementWithoutMessages])
  }

  it should "return no movement ids when the db is empty" in {
    // the collection is empty at this point due to DefaultPlayMongoRepositorySupport
    val result                    = await(repository.getMovements(EORINumber("FR999"), MovementType.Departure, None, None, None, None, None, None, None).value)
    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(0))
    paginationMovementSummary.movementSummary should be(Vector.empty[MovementWithoutMessages])
  }

  it should "return no movement ids for an MRN that doesn't exist" in {
    GetMovementsSetup.setup()
    val result = await(
      repository.getMovements(EORINumber("FR999"), MovementType.Departure, None, None, Some(MovementReferenceNumber("invalid")), None, None, None, None).value
    )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(0))
    paginationMovementSummary.movementSummary should be(Vector.empty[MovementWithoutMessages])
  }

  "getArrivals" should
    "return a list of an arrival responses for the supplied EORI sorted by last updated, latest first" in {
      GetMovementsSetup.setup()
      val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Arrival, None, None, None, None, None, None, None).value)

      val paginationMovementSummary = result.toOption.get

      paginationMovementSummary.totalCount should be(TotalCount(2))

      paginationMovementSummary.movementSummary should be(
        Vector(
          expectedMovementSummary(GetMovementsSetup.arrivalGB2),
          expectedMovementSummary(GetMovementsSetup.arrivalGB1)
        )
      )
    }

  it should "return a list of an arrival movement responses for the supplied EORI if there are movements that were updated since the given time" in {
    val dateTime = instant
    GetMovementsSetup.setup()
    await(repository.collection.insertOne(GetMovementsSetup.arrivalGB3).toFuture())
    val result = await(repository.getMovements(GetMovementsSetup.eoriGB, MovementType.Arrival, Some(dateTime), None, None, localReferenceNumber = None).value)

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(2))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.arrivalGB2),
        expectedMovementSummary(GetMovementsSetup.arrivalGB1)
      )
    )
  }

  it should "return a list of an arrival movement responses for the supplied EORI if there are movements that matched with passed movementEORI" in {
    GetMovementsSetup.setup()
    await(repository.collection.insertOne(GetMovementsSetup.arrivalGB4).toFuture())
    val result = await(
      repository
        .getMovements(GetMovementsSetup.eoriGB, MovementType.Arrival, None, Some(GetMovementsSetup.movementEORI), None, localReferenceNumber = None)
        .value
    )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(2))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.arrivalGB2),
        expectedMovementSummary(GetMovementsSetup.arrivalGB1)
      )
    )
  }

  it should "return a list of an arrival movement responses for the supplied EORI if there are movements that were updated since the given time and passed movementEORI" in {
    val dateTime = instant
    GetMovementsSetup.setup()
    await(repository.collection.insertOne(GetMovementsSetup.arrivalGB3).toFuture())
    val result =
      await(
        repository
          .getMovements(GetMovementsSetup.eoriGB, MovementType.Arrival, Some(dateTime), Some(GetMovementsSetup.movementEORI), None, localReferenceNumber = None)
          .value
      )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(2))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.arrivalGB2),
        expectedMovementSummary(GetMovementsSetup.arrivalGB1)
      )
    )
  }

  it should "return a list of arrival movement responses for the supplied EORI if there are movements that matched with passed MRN" in {
    await(repository.collection.insertOne(GetMovementsSetup.arrivalGB3).toFuture())
    val result =
      await(
        repository
          .getMovements(
            GetMovementsSetup.eoriGB,
            MovementType.Arrival,
            None,
            None,
            GetMovementsSetup.arrivalGB3.movementReferenceNumber,
            None,
            None,
            None,
            None
          )
          .value
      )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(1))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.arrivalGB3)
      )
    )
  }

  it should "return a list of arrival movement responses for the supplied EORI if there are movements that matched with partial match MRN" in {
    await(repository.collection.insertOne(GetMovementsSetup.arrivalGB5).toFuture())
    await(repository.collection.insertOne(GetMovementsSetup.arrivalGB6).toFuture())
    val result =
      await(
        repository
          .getMovements(GetMovementsSetup.eoriGB, MovementType.Arrival, None, None, Some(MovementReferenceNumber("27WF9")), None, None, None, None)
          .value
      )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(2))

    paginationMovementSummary.movementSummary should be(
      Vector(
        expectedMovementSummary(GetMovementsSetup.arrivalGB6),
        expectedMovementSummary(GetMovementsSetup.arrivalGB5)
      )
    )
  }

  it should "return no arrival ids for an EORI that doesn't exist" in {
    GetMovementsSetup.setup()
    val result = await(repository.getMovements(EORINumber("FR999"), MovementType.Arrival, None, None, None, None, None, None, None).value)

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(0))

    paginationMovementSummary.movementSummary should be(Vector.empty[MovementWithoutMessages])
  }

  it should "return no arrival ids when the db is empty" in {
    // the collection is empty at this point due to DefaultPlayMongoRepositorySupport
    val result                    = await(repository.getMovements(EORINumber("FR999"), MovementType.Arrival, None, None, None, None, None, None, None).value)
    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(0))
    paginationMovementSummary.movementSummary should be(Vector.empty[MovementWithoutMessages])
  }

  it should "return no movement ids for an MRN that doesn't exist" in {
    GetMovementsSetup.setup()
    val result = await(
      repository.getMovements(EORINumber("FR999"), MovementType.Arrival, None, None, Some(MovementReferenceNumber("invalid")), None, None, None, None).value
    )

    val paginationMovementSummary = result.toOption.get

    paginationMovementSummary.totalCount should be(TotalCount(0))

    paginationMovementSummary.movementSummary should be(Vector.empty[MovementWithoutMessages])
  }

  object GetMovementsSetup {

    val eoriGB: EORINumber        = arbitrary[EORINumber].sample.value
    val eoriXI: EORINumber        = arbitrary[EORINumber].sample.value
    val movementEORI: EORINumber  = arbitrary[EORINumber].sample.value
    val message: MongoMessage     = arbitrary[MongoMessage].sample.value
    val lrn: LocalReferenceNumber = arbitrary[LocalReferenceNumber].sample.value

    val departureGB1: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        created = instant,
        updated = instant,
        messages = Vector(message),
        localReferenceNumber = Some(lrn)
      )

    val arrivalGB1: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Arrival,
        created = instant,
        updated = instant,
        messages = Vector(message)
      )

    val mrnGen: Gen[MovementReferenceNumber] = arbitrary[MovementReferenceNumber]

    val departureXi1: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        enrollmentEORINumber = eoriXI,
        movementEORINumber = Some(movementEORI),
        updated = instant.plusMinutes(1),
        movementReferenceNumber = mrnGen.sample
      )

    val departureXi2: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        enrollmentEORINumber = eoriXI,
        movementEORINumber = Some(movementEORI),
        updated = instant.minusMinutes(3),
        movementReferenceNumber = mrnGen.sample,
        localReferenceNumber = Some(lrn)
      )

    val departureGB2: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(1),
        movementReferenceNumber = mrnGen.sample,
        localReferenceNumber = Some(lrn)
      )

    val departureGB3: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = mrnGen.sample,
        localReferenceNumber = Some(lrn)
      )

    val departureGB4: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(EORINumber("1234AB")),
        movementType = MovementType.Departure,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = mrnGen.sample,
        localReferenceNumber = Some(lrn)
      )

    val departureGB5: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(EORINumber("1234AB")),
        movementType = MovementType.Departure,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = Some(MovementReferenceNumber("27WF9X1FQ9RCKN0TM5")),
        localReferenceNumber = Some(LocalReferenceNumber("3CnsTh79I7hyOy6"))
      )

    val departureGB6: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(EORINumber("1234AB")),
        movementType = MovementType.Departure,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = Some(MovementReferenceNumber("27wF9X1FQ9RCKN0TM3")),
        localReferenceNumber = Some(LocalReferenceNumber("3CnsTh79I7hyOy7"))
      )

    val departureGB7: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(EORINumber("1234AB")),
        movementType = MovementType.Departure,
        updated = instant.minusMinutes(7),
        movementReferenceNumber = Some(MovementReferenceNumber("27wF9X1FQ9RCKN0TM3"))
      )

    val arrivalGB2: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Arrival,
        updated = instant.plusMinutes(1),
        movementReferenceNumber = mrnGen.sample,
        localReferenceNumber = None
      )

    val arrivalGB3: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Arrival,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = mrnGen.sample,
        localReferenceNumber = None
      )

    val arrivalGB4: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(EORINumber("1234AB")),
        movementType = MovementType.Arrival,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = mrnGen.sample,
        localReferenceNumber = None
      )

    val arrivalGB5: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Arrival,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = Some(MovementReferenceNumber("27WF9X1FQ9RCKN0TM5"))
      )

    val arrivalGB6: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Arrival,
        updated = instant.minusMinutes(3),
        movementReferenceNumber = Some(MovementReferenceNumber("27wF9X1FQ9RCKN0TM6"))
      )

    // For Pagination

    val departureGB10: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("10"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(10),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB11: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("11"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(11),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB12: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("12"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(12),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB13: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("13"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(13),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB14: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("14"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(14),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB15: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("15"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(15),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB16: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("16"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(16),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB17: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("17"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(17),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB18: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("18"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(18),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB19: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("19"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(19),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB20: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("20"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(20),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB21: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("21"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(21),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB22: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("22"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(22),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB23: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("23"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(23),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB24: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("24"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(24),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB25: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("25"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(25),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB26: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("26"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(26),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB27: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("27"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(27),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB28: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("28"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(28),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB29: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("29"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(29),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB30: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("30"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(30),
        movementReferenceNumber = mrnGen.sample
      )

    val departureGB31: MongoMovement =
      arbitrary[MongoMovement].sample.value.copy(
        _id = MovementId("31"),
        enrollmentEORINumber = eoriGB,
        movementEORINumber = Some(movementEORI),
        movementType = MovementType.Departure,
        updated = instant.plusMinutes(31),
        movementReferenceNumber = mrnGen.sample
      )

    def setup(): Either[MongoError, Unit] = {
      //populate db in non-time order

      await(repository.insert(departureXi2).value)
      await(repository.insert(departureGB2).value)
      await(repository.insert(departureXi1).value)
      await(repository.insert(departureGB1).value)
      await(repository.insert(arrivalGB2).value)
      await(repository.insert(arrivalGB1).value)
    }

    def setupMessages(dateTime: OffsetDateTime): Vector[MongoMessage] =
      Vector(
        ArbitraryMessage.arbitrary.sample.value.copy(received = dateTime.plusMinutes(3), uri = None),
        ArbitraryMessage.arbitrary.sample.value.copy(received = dateTime.plusMinutes(2), uri = None),
        ArbitraryMessage.arbitrary.sample.value.copy(received = dateTime.plusMinutes(1), uri = None),
        ArbitraryMessage.arbitrary.sample.value.copy(received = dateTime, uri = None),
        ArbitraryMessage.arbitrary.sample.value.copy(received = dateTime.minusMinutes(1), uri = None),
        ArbitraryMessage.arbitrary.sample.value.copy(received = dateTime.minusMinutes(2), uri = None),
        ArbitraryMessage.arbitrary.sample.value.copy(received = dateTime.minusMinutes(3), uri = None)
      )

    def setupMessagesWithOutBody(dateTime: OffsetDateTime): Vector[MongoMessage] =
      Vector(
        ArbitraryMessage.arbitrary.sample.value.copy(received = dateTime.plusMinutes(3), uri = None, body = None),
        ArbitraryMessage.arbitrary.sample.value.copy(received = dateTime.plusMinutes(2), uri = None, body = None),
        ArbitraryMessage.arbitrary.sample.value.copy(received = dateTime.plusMinutes(1), uri = None, body = None),
        ArbitraryMessage.arbitrary.sample.value.copy(received = dateTime, uri = None, body = None),
        ArbitraryMessage.arbitrary.sample.value.copy(received = dateTime.minusMinutes(1), uri = None, body = None),
        ArbitraryMessage.arbitrary.sample.value.copy(received = dateTime.minusMinutes(2), uri = None, body = None),
        ArbitraryMessage.arbitrary.sample.value.copy(received = dateTime.minusMinutes(3), uri = None, body = None)
      )

    def setupPagination(): Either[MongoError, Unit] = {
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
    val message1 = arbitrary[MongoMessage].sample.value.copy(body = None, messageType = Some(MessageType.DeclarationData), triggerId = None)

    val departureID = arbitrary[MovementId].sample.value
    val departure =
      arbitrary[MongoMovement].sample.value
        .copy(
          _id = departureID,
          created = instant,
          updated = instant,
          messages = Vector(message1)
        )

    await(
      repository.collection.insertOne(departure).toFuture()
    )

    val message2 =
      arbitrary[MongoMessage].sample.value.copy(
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

    val messages = movement.messages
    movement.updated shouldEqual receivedInstant
    messages.length should be(2)
    messages.toList should contain(message1)
    messages.toList should contain(message2)

  }

  "updateMessages" should "if MrnAllocated, update MRN field when message type is MRNAllocated, as well as messages and set updated parameter to when the latest message was received" in {

    val message1 = arbitrary[MongoMessage].sample.value.copy(body = None, messageType = Some(MessageType.MrnAllocated), triggerId = None)

    val departureId = arbitrary[MovementId].sample.value
    val departure =
      arbitrary[MongoMovement].sample.value
        .copy(
          _id = departureId,
          created = instant,
          updated = instant,
          movementReferenceNumber = None,
          messages = Vector(message1)
        )

    await(
      repository.collection.insertOne(departure).toFuture()
    )

    val message2 =
      arbitrary[MongoMessage].sample.value.copy(
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

    val messages = movement.messages
    movement.updated shouldEqual receivedInstant
    messages.length should be(2)
    messages.toList should contain(message1)
    messages.toList should contain(message2)
    movement.movementReferenceNumber should be(Some(mrn))

  }

  "updateMessages" should "return error if there is no matching movement with the given id" in {

    val movementId = arbitrary[MovementId].sample.value

    val message =
      arbitrary[MongoMessage].sample.value
        .copy(body = None, messageType = Some(MessageType.DepartureOfficeRejection), triggerId = Some(MessageId(movementId.value)))

    val result = await(
      repository.attachMessage(movementId, message, Some(arbitrary[MovementReferenceNumber].sample.get), instant).value
    )

    result should be(Left(MongoError.DocumentNotFound(s"No movement found with the given id: ${movementId.value}")))

  }

  "updateMessage" should "update the existing message with both status and object store url" in {

    val message1 =
      arbitrary[MongoMessage].sample.value.copy(
        body = None,
        messageType = Some(MessageType.DeclarationData),
        triggerId = None,
        status = Some(MessageStatus.Pending)
      )

    val departureMovement =
      arbitrary[MongoMovement].sample.value
        .copy(
          created = instant,
          updated = instant,
          messages = Vector(message1)
        )

    await(
      repository.collection.insertOne(departureMovement).toFuture()
    )

    val updateData = MongoMessageUpdateData(
      Some(ObjectStoreURI("common-transit-convention-traders/some-url.xml")),
      None,
      None,
      MessageStatus.Success,
      None,
      None
    )

    val result = await(
      repository.updateMessage(departureMovement._id, message1.id, updateData, receivedInstant).value
    )

    result should be(Right(()))

    val movement = await {
      repository.collection.find(Filters.eq("_id", departureMovement._id.value)).first().toFuture()
    }

    val messages = movement.messages
    movement.updated shouldEqual receivedInstant
    messages.length should be(1)
    messages.head.status shouldBe Some(updateData.status)
    messages.head.uri.value.toString shouldBe updateData.objectStoreURI.get.value

  }

  "updateMessage" should "update an existing message with status only" in {
    val message1 =
      arbitrary[MongoMessage].sample.value.copy(
        body = None,
        messageType = Some(MessageType.DeclarationData),
        triggerId = None,
        status = Some(MessageStatus.Pending)
      )

    val departureMovement =
      arbitrary[MongoMovement].sample.value
        .copy(
          created = instant,
          updated = instant,
          messages = Vector(message1)
        )

    await(
      repository.collection.insertOne(departureMovement).toFuture()
    )

    val updateMessageData = MongoMessageUpdateData(
      None,
      None,
      None,
      MessageStatus.Failed,
      None,
      None
    )

    val result = await(
      repository.updateMessage(departureMovement._id, message1.id, updateMessageData, receivedInstant).value
    )

    result should be(Right(()))

    val movement = await {
      repository.collection.find(Filters.eq("_id", departureMovement._id.value)).first().toFuture()
    }

    val messages = movement.messages
    movement.updated shouldEqual receivedInstant
    messages.length should be(1)
    messages.head.status shouldBe Some(updateMessageData.status)

  }

  "updateMessage" should "update the existing message with both status and messageType" in {

    val message1 =
      arbitrary[MongoMessage].sample.value.copy(
        body = None,
        messageType = Some(MessageType.DeclarationData),
        triggerId = None,
        status = Some(MessageStatus.Pending)
      )

    val departureMovement =
      arbitrary[MongoMovement].sample.value
        .copy(
          created = instant,
          updated = instant,
          messages = Vector(message1)
        )

    await(
      repository.collection.insertOne(departureMovement).toFuture()
    )

    val updateMessageData = MongoMessageUpdateData(
      None,
      None,
      None,
      MessageStatus.Success,
      Some(MessageType.DeclarationAmendment),
      None
    )

    val result = await(
      repository.updateMessage(departureMovement._id, message1.id, updateMessageData, receivedInstant).value
    )

    result should be(Right(()))

    val movement = await {
      repository.collection.find(Filters.eq("_id", departureMovement._id.value)).first().toFuture()
    }

    val messages = movement.messages
    movement.updated shouldEqual receivedInstant
    messages.length should be(1)
    messages.head.status shouldBe Some(updateMessageData.status)
    messages.head.messageType.get.code shouldBe updateMessageData.messageType.get.code
  }

  "updateMessage" should "update the existing message with status, object store url, size, and messageType" in {

    val message1 =
      arbitrary[MongoMessage].sample.value.copy(
        body = None,
        messageType = Some(MessageType.DeclarationData),
        triggerId = None,
        status = Some(MessageStatus.Pending)
      )

    val departureMovement =
      arbitrary[MongoMovement].sample.value
        .copy(
          created = instant,
          updated = instant,
          messages = Vector(message1)
        )

    await(
      repository.collection.insertOne(departureMovement).toFuture()
    )

    val updateMessageData = MongoMessageUpdateData(
      Some(ObjectStoreURI("common-transit-convention-traders/some-url.xml")),
      None,
      Option(4L),
      MessageStatus.Success,
      Some(MessageType.DeclarationAmendment),
      None
    )

    val result = await(
      repository.updateMessage(departureMovement._id, message1.id, updateMessageData, receivedInstant).value
    )

    result should be(Right(()))

    whenReady(
      repository.collection.find(Filters.eq("_id", departureMovement._id.value)).first().toFuture()
    ) {
      movement =>
        val messages = movement.messages
        movement.updated shouldEqual receivedInstant
        messages.length should be(1)

        val message = messages.head
        message.status shouldBe Some(updateMessageData.status)
        message.uri.value.toString shouldBe updateMessageData.objectStoreURI.get.value
        message.messageType.get.code shouldBe updateMessageData.messageType.get.code
        message.size shouldBe Some(4L)
    }
  }

  "updateMessage" should "update the existing message with status, object store url, size, messageType and generationDate" in {

    val message1 =
      arbitrary[MongoMessage].sample.value.copy(
        body = None,
        messageType = Some(MessageType.DeclarationData),
        triggerId = None,
        status = Some(MessageStatus.Pending)
      )

    val departureMovement =
      arbitrary[MongoMovement].sample.value
        .copy(
          created = instant,
          updated = instant,
          messages = Vector(message1)
        )

    await(
      repository.collection.insertOne(departureMovement).toFuture()
    )

    val now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS)

    val updateMessageData = MongoMessageUpdateData(
      objectStoreURI = Some(ObjectStoreURI("common-transit-convention-traders/some-url.xml")),
      body = None,
      status = MessageStatus.Success,
      size = Option(4L),
      messageType = Some(MessageType.DeclarationAmendment),
      generationDate = Some(now)
    )

    val result = await(
      repository.updateMessage(departureMovement._id, message1.id, updateMessageData, receivedInstant).value
    )

    result should be(Right(()))

    whenReady(
      repository.collection.find(Filters.eq("_id", departureMovement._id.value)).first().toFuture()
    ) {
      movement =>
        movement.updated shouldEqual receivedInstant
        movement.messages.length should be(1)

        val message = movement.messages.head
        message.status shouldBe Some(updateMessageData.status)
        message.uri.value.toString shouldBe updateMessageData.objectStoreURI.get.value
        message.messageType.get.code shouldBe updateMessageData.messageType.get.code
        message.size shouldBe Some(4L)
        message.generated shouldBe Some(now)
    }
  }

  "updateMessage" should "return error if there is no matching movement with the given id" in {

    val movementId = arbitrary[MovementId].sample.value

    val message =
      arbitrary[MongoMessage].sample.value
        .copy(body = None, messageType = Some(MessageType.DepartureOfficeRejection), triggerId = Some(MessageId(movementId.value)))

    val updateMessageData = MongoMessageUpdateData(
      None,
      None,
      None,
      MessageStatus.Failed,
      None,
      None
    )

    val result = await(
      repository.updateMessage(movementId, message.id, updateMessageData, instant).value
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
        repository.updateMovement(movementId, Some(eori), Some(mrn), None, None, instant).value
      )

      result should be(Left(MongoError.DocumentNotFound(s"No movement found with the given id: ${movementId.value}")))
  }

}
