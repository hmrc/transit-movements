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

package controllers

import cats.data.NonEmptyList
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import models.MessageType
import models.errors.InternalServiceError
import models.errors.SchemaValidationError
import models.errors.TransitMovementError
import models.errors.UpstreamServiceError
import models.errors.XmlValidationError
import models.values.DepartureId
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.slf4j.LoggerFactory
import play.api.Logging
import uk.gov.hmrc.http.UpstreamErrorResponse

class ErrorLoggingSpec
  extends AnyFlatSpec
  with Matchers
  with ScalaCheckPropertyChecks
  with ScalaFutures
  with Logging
  with ErrorLogging {

  def withLogAppender[A](test: ListAppender[ILoggingEvent] => A) = {
    val slf4jLogger   = LoggerFactory.getLogger(getClass())
    val listAppender  = new ListAppender[ILoggingEvent]()
    val logbackLogger = slf4jLogger.asInstanceOf[Logger]
    listAppender.start()
    logbackLogger.detachAndStopAllAppenders()
    logbackLogger.addAppender(listAppender)
    try test(listAppender)
    finally logbackLogger.detachAndStopAllAppenders()
  }

  "ErrorLogging.logServiceError" should "do nothing when there is no error" in forAll { i: Int =>
    withLogAppender { appender =>
      logServiceError("running tests", Right(i)).futureValue
      assert(appender.list.isEmpty, appender.list)
    }
  }

  it should "log an error when there is an UpstreamServiceError" in withLogAppender { appender =>
    val error = UpstreamErrorResponse("Argh!!!", 400)
    logServiceError("running tests", Left(UpstreamServiceError.causedBy(error))).futureValue
    assert(!appender.list.isEmpty, appender.list)
    val event = appender.list.get(0)
    event.getLevel shouldBe Level.ERROR
    event.getMessage shouldBe "Error when calling upstream service"
    event.getThrowableProxy.getMessage shouldBe "Argh!!!"
  }

  it should "log an error when there is an InternalServiceError with root cause exception" in withLogAppender {
    appender =>
      val error = InternalServiceError.causedBy(new RuntimeException("Whoops!!!"))
      logServiceError("running tests", Left(error)).futureValue
      assert(!appender.list.isEmpty, appender.list)
      val event = appender.list.get(0)
      event.getLevel shouldBe Level.ERROR
      event.getMessage shouldBe "Error when running tests"
      event.getThrowableProxy.getClassName shouldBe classOf[RuntimeException].getName
      event.getThrowableProxy.getMessage shouldBe "Whoops!!!"
  }

  it should "log an error when there is an InternalServiceError without root cause exception" in withLogAppender {
    appender =>
      val error = TransitMovementError.internalServiceError()
      logServiceError("running tests", Left(error)).futureValue
      assert(!appender.list.isEmpty, appender.list)
      val event = appender.list.get(0)
      event.getLevel shouldBe Level.ERROR
      event.getMessage shouldBe "Error when running tests: Internal server error"
      event.getThrowableProxy shouldBe null
  }

  it should "log an error when there is a BadRequestError" in withLogAppender { appender =>
    val error = TransitMovementError.badRequestError("I don't like it!!!")
    logServiceError("running tests", Left(error)).futureValue
    assert(!appender.list.isEmpty, appender.list)
    val event = appender.list.get(0)
    event.getLevel shouldBe Level.ERROR
    event.getMessage shouldBe "Error in request data: I don't like it!!!"
    event.getThrowableProxy shouldBe null
  }

  it should "log an error when there is an XmlValidationError" in withLogAppender { appender =>
    val error = XmlValidationError(
      MessageType.DeclarationData,
      NonEmptyList.of(
        SchemaValidationError(0, 1, "Value 'ABC12345' is not facet-valid with respect to pattern"),
        SchemaValidationError(2, 3, "The value 'ABC12345' of element 'DatOfPreMES9' is not valid")
      )
    )
    logServiceError("running tests", Left(error)).futureValue
    assert(!appender.list.isEmpty, appender.list)
    val event = appender.list.get(0)
    event.getLevel shouldBe Level.ERROR
    event.getMessage shouldBe "Error when running tests: Error while validating IE015 message"
    event.getThrowableProxy shouldBe null
  }

  it should "not log anything when there is a NotFoundError" in withLogAppender { appender =>
    val departureId = DepartureId(DepartureId.fromHex("617b162bb367d2bc"))
    val error       = TransitMovementError.notFoundError(departureId)
    logServiceError("running tests", Left(error)).futureValue
    assert(appender.list.isEmpty, appender.list)
  }
}
