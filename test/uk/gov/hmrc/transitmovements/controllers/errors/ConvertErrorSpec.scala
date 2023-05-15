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

package uk.gov.hmrc.transitmovements.controllers.errors

import cats.syntax.all._
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Logging
import uk.gov.hmrc.transitmovements.controllers.errors.ErrorCode.BadRequest
import uk.gov.hmrc.transitmovements.controllers.errors.ErrorCode.Conflict
import uk.gov.hmrc.transitmovements.controllers.errors.ErrorCode.InternalServerError
import uk.gov.hmrc.transitmovements.controllers.errors.ErrorCode.NotFound
import uk.gov.hmrc.transitmovements.controllers.errors.MessageTypeExtractError.InvalidMessageType
import uk.gov.hmrc.transitmovements.controllers.errors.MessageTypeExtractError.NoHeaderFound

import java.time.format.DateTimeParseException
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ConvertErrorSpec extends AnyFreeSpec with Matchers with OptionValues with ScalaFutures with MockitoSugar {

  object Harness extends ConvertError with Logging

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

    for (error <- Seq(NoElementFound("test"), TooManyElementsFound("test"), BadDateTime("test", new DateTimeParseException("test", "error", 0))))
      s"${error.getClass.toString().split("\\$").last} should result in BadRequest status" in {
        val input = Left[ParseError, Unit](error).toEitherT[Future]
        whenReady(input.asPresentation.value) {
          _.left.toOption.get.code mustBe BadRequest
        }
      }

    "UnexpectedError should result in InternalServerError status" in {
      val input = Left[ParseError, Unit](UnexpectedError(None)).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _.left.toOption.get.code mustBe InternalServerError
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

    for (error <- Seq(UnexpectedError(None), InsertNotAcknowledged("test"), UpdateNotAcknowledged("test")))
      s"${error.getClass.toString().split("\\$").last} should result in InternalServerError status" in {
        val input = Left[MongoError, Unit](error).toEitherT[Future]
        whenReady(input.asPresentation.value) {
          _.left.toOption.get.code mustBe InternalServerError
        }
      }

    "Conflict should result in Conflict status" in {
      val input = Left[MongoError, Unit](ConflictError("test")).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _.left.toOption.get.code mustBe Conflict
      }
    }

    "DocumentNotFound should result in NotFound status" in {
      val input = Left[MongoError, Unit](DocumentNotFound("test")).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _.left.toOption.get.code mustBe NotFound
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
      val input = Right[MessageTypeExtractError, Unit](()).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Right(())
      }
    }

    for (error <- Seq(NoHeaderFound("test"), InvalidMessageType("test")))
      s"${error.getClass.toString().split("\\$").last} should result in InternalServerError status" in {
        val input = Left[MessageTypeExtractError, Unit](error).toEitherT[Future]
        whenReady(input.asPresentation.value) {
          _.left.toOption.get.code mustBe BadRequest
        }
      }
  }

  "ObjectStore error" - {
    import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError
    import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError._

    "for a success" in {
      val input = Right[ObjectStoreError, Unit](()).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Right(())
      }
    }

    "for a failure" in {
      val exception = new Exception("unexpected failure")
      val input     = Left[ObjectStoreError, Unit](UnexpectedError(Some(exception))).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(InternalServiceError("Internal server error", InternalServerError, Some(exception)))
      }
    }

    "FileNotFound should result BadRequest status" in {
      val input = Left[ObjectStoreError, Unit](FileNotFound("test")).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _.left.toOption.get.code mustBe BadRequest
      }
    }
  }

  "objectStoreErrorWithNotFoundConverter" - {
    import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError
    import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError._

    "for a success" in {
      val input = Right[ObjectStoreError, Unit](()).toEitherT[Future]
      whenReady(input.asPresentation(objectStoreErrorWithNotFoundConverter, implicitly[ExecutionContext]).value) {
        _ mustBe Right(())
      }
    }

    "for a failure" in {
      val exception = new Exception("unexpected failure")
      val input     = Left[ObjectStoreError, Unit](UnexpectedError(Some(exception))).toEitherT[Future]
      whenReady(input.asPresentation(objectStoreErrorWithNotFoundConverter, implicitly[ExecutionContext]).value) {
        _ mustBe Left(InternalServiceError("Internal server error", InternalServerError, Some(exception)))
      }
    }

    "FileNotFound should result BadRequest status" in {
      val input = Left[ObjectStoreError, Unit](FileNotFound("test")).toEitherT[Future]
      whenReady(input.asPresentation(objectStoreErrorWithNotFoundConverter, implicitly[ExecutionContext]).value) {
        case Left(StandardError("file not found at location: test", NotFound)) => succeed
        case x                                                                 => fail(s"Expected Left(NotFound), got $x")
      }
    }
  }

}
