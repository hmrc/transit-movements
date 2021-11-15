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

import models.Message
import models.MessageType
import models.request.DeclarationDataRequest
import models.values.CustomsOfficeNumber
import models.values.DepartureId
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
import uk.gov.hmrc.objectstore.client.Path

import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import scala.concurrent.ExecutionContext.Implicits.global

class MessagesRepositorySpec
  extends AnyFlatSpec
  with Matchers
  with ScalaCheckPropertyChecks
  with FutureAwaits
  with DefaultAwaitTimeout
  with Transactions
  with Logging
  with DefaultPlayMongoRepositorySupport[Message] {

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

  override lazy val repository = new MessagesRepositoryImpl(
    mongoComponent,
    clock,
    random
  )

  "MessagesRepository" should "have the correct name" in {
    repository.collectionName shouldBe "messages"
  }

  it should "insert messages based on declaration data" in {
    val departureId  = DepartureId(DepartureId.fromHex("612f48afd4d95138"))
    val eoriNumber   = EoriNumber("GB123456789000")
    val lrn          = LocalReferenceNumber("ABC1234567890")
    val prepDateTime = OffsetDateTime.now
    val messagePath  = Path.Directory("foo/bar").file("baz.xml")

    val request = DeclarationDataRequest(
      eoriNumber,
      prepDateTime,
      lrn,
      CustomsOfficeNumber("GB000060")
    )

    val messageId = await(withTransaction { session =>
      repository.insertMessage(session, departureId, request, messagePath)
    })

    val messages = await(find(Filters.eq("_id", messageId.id.toArray)))

    messages should not be empty

    messages.head shouldBe Message(
      messageId,
      departureId,
      prepDateTime,
      MessageType.DeclarationData,
      messagePath
    )
  }
}
