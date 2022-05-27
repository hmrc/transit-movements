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

import cats.data.EitherT
import org.mockito.scalatest.MockitoSugar
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.mvc.Results.Accepted
import play.api.mvc.Results.Status
import uk.gov.hmrc.transitmovements.services.errors.ParseError

import java.time.format.DateTimeParseException
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.Await
import scala.concurrent.Future
import scala.util.Success
import scala.util.Try

class ErrorTranslatorSpec extends AnyFreeSpec with Matchers with MockitoSugar {

  object ErrorTranslatorHarness extends ErrorTranslator {

    private def generateLeftEitherT[E](error: E): EitherT[Future, E, Int] =
      EitherT(Future.successful(Try("invalid".toInt).map(Right(_)).getOrElse(Left(error))))

    def checkBaseErrorCode[E](error: E): Status = {

      val generalErrorEitherT                               = generateLeftEitherT(error)
      val baseErrorEitherT: EitherT[Future, BaseError, Int] = generalErrorEitherT // implicit conversion from E -> BaseError

      Await
        .ready(
          baseErrorEitherT.value.map {
            case Left(x: BaseError) => Status(x.code.statusCode)
            case Right(_)           => Accepted
          },
          Duration.Inf
        )
        .value
        .map(
          _ match {
            case Success(t) => t
            case _          => Accepted
          }
        )
        .get

    }
  }

  Seq(
    ParseError.NoElementFound("x"),
    ParseError.TooManyElementsFound("y"),
    ParseError.BadDateTime("dte", new DateTimeParseException("error", "parsedData", 200))
  ).foreach {
    parseError =>
      s"Converts ${parseError.toString.split('(')(0)} into BadRequest" in {

        ErrorTranslatorHarness.checkBaseErrorCode(parseError) mustBe Status(ErrorCode.BadRequest.statusCode)
      }
  }

  "Converts Unknown into InternalServerError" in {

    val unknownError = ParseError.Unknown()

    ErrorTranslatorHarness.checkBaseErrorCode(unknownError) mustBe Status(ErrorCode.InternalServerError.statusCode)
  }

}
