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

package test.uk.gov.hmrc.transitmovements.testOnly.controllers

import org.mockito.Mockito.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.test.DefaultAwaitTimeout
import play.api.test.FakeRequest
import play.api.test.FutureAwaits
import play.api.test.Helpers.*
import test.uk.gov.hmrc.transitmovements.it.generators.ModelGenerators
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.crypto.SymmetricCryptoFactory
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.transitmovements.config.AppConfig
import uk.gov.hmrc.transitmovements.repositories.MovementsRepositoryImpl
import uk.gov.hmrc.transitmovements.testOnly.controllers.TestOnlyController
import uk.gov.hmrc.transitmovements.models.formats.MongoFormats
import uk.gov.hmrc.transitmovements.models.mongo.write.MongoMovement

import scala.concurrent.ExecutionContext.Implicits.global

class TestOnlyControllerSpec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with FutureAwaits
    with DefaultAwaitTimeout
    with ModelGenerators
    with OptionValues
    with MockitoSugar {

  val appConfig: AppConfig = mock[AppConfig]
  when(appConfig.documentTtl).thenReturn(1000000L) // doesn't matter, just something will do
  when(appConfig.encryptionTolerantRead).thenReturn(true)
  when(appConfig.encryptionKey).thenReturn("7CYXDDh/UbNDY1UV8bkxvTzur3pCUzsAvMVH+HsRWbY=")

  lazy val mongoComponent: MongoComponent = {
    val databaseName: String = "test-movements-testonly"
    val mongoUri: String     = s"mongodb://localhost:27017/$databaseName?retryWrites=false"
    MongoComponent(mongoUri)
  }

  implicit val crypto: Encrypter & Decrypter = SymmetricCryptoFactory.aesGcmCrypto(appConfig.encryptionKey)
  val mongoFormats: MongoFormats             = new MongoFormats(appConfig)
  val MongoFormats                           = new MongoFormats(appConfig)

  lazy val repository = new MovementsRepositoryImpl(appConfig, mongoComponent, mongoFormats)
  lazy val Repository = new MovementsRepositoryImpl(appConfig, mongoComponent, MongoFormats)

  lazy val controller = new TestOnlyController(stubControllerComponents(), Repository, repository)

  def Count: Long = await(Repository.collection.countDocuments().head)
  def count: Long = await(repository.collection.countDocuments().head)

  "dropCollection" should "drop the movements collection and return OK" in forAll(
    arbitrary[MongoMovement]
  ) {
    movement =>
      await(repository.insert(movement).value)

      Count shouldBe 1
      count shouldBe 1

      val result = controller.dropCollection()(FakeRequest("DELETE", "/"))
      status(result) shouldBe OK

      Count shouldBe 0
      count shouldBe 0
  }
}
