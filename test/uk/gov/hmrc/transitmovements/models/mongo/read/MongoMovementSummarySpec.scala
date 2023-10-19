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

package uk.gov.hmrc.transitmovements.models.mongo.read

import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.transitmovements.generators.ModelGenerators
import uk.gov.hmrc.transitmovements.models.MovementWithoutMessages

class MongoMovementSummarySpec extends AnyFreeSpec with Matchers with ScalaCheckDrivenPropertyChecks with ModelGenerators {

  "MongoMovementSummary#asMovementWithoutMessages" - {
    "should convert to an appropriate MovementWithoutMessages" in forAll(arbitrary[MongoMovementSummary]) {
      movement =>
        movement.asMovementWithoutMessages mustBe MovementWithoutMessages(
          movement._id,
          movement.enrollmentEORINumber,
          movement.movementEORINumber,
          movement.movementReferenceNumber,
          movement.localReferenceNumber,
          movement.created,
          movement.updated
        )
    }
  }

}
