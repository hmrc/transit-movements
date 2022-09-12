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

package uk.gov.hmrc.transitmovements.controllers.errors

import cats.syntax.all._
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.transitmovements.controllers.errors.ErrorCode.BadRequest
import uk.gov.hmrc.transitmovements.controllers.errors.ErrorCode.InternalServerError
import uk.gov.hmrc.transitmovements.controllers.errors.HeaderExtractError.NoHeaderFound

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ConvertErrorSpec extends AnyFreeSpec with Matchers with OptionValues with ScalaFutures with MockitoSugar {

  object Harness extends ConvertError

  import Harness._

  "Parse error" - {
    import uk.gov.hmrc.transitmovements.services.errors.ParseError
    import uk.gov.hmrc.transitmovements.services.errors.ParseError._

    "for a success" in {
      val input = Right[ParseError, Unit](()).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Right(())
      }
    }

    "for a failure" in {
      val input = Left[ParseError, Unit](NoElementFound("test")).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(StandardError("Element test not found", BadRequest))
      }
    }
  }

  "Mongo error" - {
    import uk.gov.hmrc.transitmovements.services.errors.MongoError
    import uk.gov.hmrc.transitmovements.services.errors.MongoError._

    "for a success" in {
      val input = Right[MongoError, Unit](()).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Right(())
      }
    }

    "for a failure" in {
      val exception = new Exception("mongo failure")
      val input     = Left[MongoError, Unit](UnexpectedError(Some(exception))).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(InternalServiceError("Internal server error", InternalServerError, Some(exception)))
      }
    }
  }

  "Stream error" - {
    import uk.gov.hmrc.transitmovements.services.errors.StreamError
    import uk.gov.hmrc.transitmovements.services.errors.StreamError._

    "for a success" in {
      val input = Right[StreamError, Unit](()).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Right(())
      }
    }

    "for a failure" in {
      val exception = new Exception("stream failure")
      val input     = Left[StreamError, Unit](UnexpectedError(Some(exception))).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(InternalServiceError("Internal server error", InternalServerError, Some(exception)))
      }
    }
  }

  "Header extract error" - {

    "for a success" in {
      val input = Right[HeaderExtractError, Unit](()).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Right(())
      }
    }

    "for a failure" in {
      val input = Left[HeaderExtractError, Unit](NoHeaderFound("Missing header")).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(StandardError("Missing header", ErrorCode.BadRequest))
      }
    }
  }

}
