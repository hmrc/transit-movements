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

package uk.gov.hmrc.transitmovements.testOnly.controllers

import org.mockito.scalatest.MockitoSugar
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.test.DefaultAwaitTimeout
import play.api.test.FakeRequest
import play.api.test.FutureAwaits
import play.api.test.Helpers._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.transitmovements.config.AppConfig
import uk.gov.hmrc.transitmovements.it.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.formats.MongoFormats
import uk.gov.hmrc.transitmovements.models.mongo.write.MongoMovement
import uk.gov.hmrc.transitmovements.repositories.MovementsRepositoryImpl

import scala.concurrent.ExecutionContext.Implicits.global

class TestOnlyControllerSpec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with FutureAwaits
    with DefaultAwaitTimeout
    with DefaultPlayMongoRepositorySupport[MongoMovement]
    with ModelGenerators
    with OptionValues
    with MockitoSugar {

  val appConfig: AppConfig = mock[AppConfig]
  when(appConfig.documentTtl).thenReturn(1000000) // doesn't matter, just something will do
  when(appConfig.encryptionTolerantRead).thenReturn(true)
  when(appConfig.encryptionKey).thenReturn("7CYXDDh/UbNDY1UV8bkxvTzur3pCUzsAvMVH+HsRWbY=")

  override lazy val mongoComponent: MongoComponent = {
    val databaseName: String = "test-movements-testonly"
    val mongoUri: String     = s"mongodb://localhost:27017/$databaseName?retryWrites=false"
    MongoComponent(mongoUri)
  }

  val mongoFormats: MongoFormats = new MongoFormats(appConfig)

  override lazy val repository = new MovementsRepositoryImpl(appConfig, mongoComponent, mongoFormats)

  lazy val controller = new TestOnlyController(stubControllerComponents(), repository)

  private def documentCount: Long = await(count())

  "dropCollection" should "drop the movements collection and return OK" in forAll(arbitrary[MongoMovement]) {
    movement =>
      await(insert(movement))
      documentCount shouldBe 1

      val result = controller.dropCollection()(FakeRequest("DELETE", "/"))
      status(result) shouldBe OK

      documentCount shouldBe 0
  }
}
