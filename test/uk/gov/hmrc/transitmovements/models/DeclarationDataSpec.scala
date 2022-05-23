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

package uk.gov.hmrc.transitmovements.models

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class DeclarationDataSpec extends AnyFreeSpec with Matchers {

  "A declaration data object build from a partial declaration data object should contain the partial declaration data" in {
    // given this partial declaration
    val partialDeclarationData = PartialDeclarationData(EORINumber("GB1234"))

    // when we build the full declaration
    val declarationData = DeclarationData.fromPartialDeclarationData(EORINumber("XI1234"), partialDeclarationData)

    // then we should expect the following
    declarationData mustEqual DeclarationData(enrolmentEoriNumber = EORINumber("XI1234"), movementEoriNumber = EORINumber("GB1234"))
  }

}
