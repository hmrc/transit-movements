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

import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.DefaultAwaitTimeout
import play.api.test.FakeRequest
import play.api.test.FutureAwaits
import play.api.test.Helpers._
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.transitmovements.it.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models._
import uk.gov.hmrc.transitmovements.repositories.MovementsRepositoryImpl

class TestOnlyControllerSpec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with FutureAwaits
    with DefaultAwaitTimeout
    with DefaultPlayMongoRepositorySupport[Movement]
    with ModelGenerators
    with OptionValues {

  implicit private lazy val app: Application = GuiceApplicationBuilder()
    .configure("play.http.router" -> "testOnlyDoNotUseInAppConf.Routes")
    .build()

  override lazy val repository: MovementsRepositoryImpl = app.injector.instanceOf[MovementsRepositoryImpl]

  private def documentCount: Long = await(repository.collection.countDocuments().toFuture())

  "dropCollection" should "drop the movements collection and return OK" in forAll(arbitrary[Movement]) {
    movement =>
      await(repository.insert(movement).value)
      documentCount shouldBe 1

      val result = route(app, FakeRequest(DELETE, s"/transit-movements/test-only/movements")).value
      status(result) shouldBe OK

      documentCount shouldBe 0
  }
}
