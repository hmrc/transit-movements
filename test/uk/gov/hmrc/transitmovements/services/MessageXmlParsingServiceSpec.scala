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

package uk.gov.hmrc.transitmovements.services

import akka.stream.scaladsl.Sink
import com.fasterxml.aalto.WFCException
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import uk.gov.hmrc.transitmovements.base.StreamTestHelpers
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.models.MessageData
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.services.errors.ParseError

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import scala.xml.NodeSeq

class MessageXmlParsingServiceSpec extends AnyFreeSpec with ScalaFutures with Matchers with TestActorSystem with StreamTestHelpers {

  private val testDate      = OffsetDateTime.now(ZoneOffset.UTC)
  private val UTCDateString = testDate.toLocalDateTime.format(DateTimeFormatter.ISO_DATE_TIME)

  implicit val defaultPatience =
    PatienceConfig(timeout = Span(6, Seconds))

  val validXml: NodeSeq =
    <CC015C>
      <HolderOfTheTransitProcedure>
        <identificationNumber>GB1234</identificationNumber>
      </HolderOfTheTransitProcedure>
      <preparationDateAndTime>{UTCDateString}</preparationDateAndTime>
    </CC015C>

  val invalidMessageType: NodeSeq =
    <CCInvalid>
      <HolderOfTheTransitProcedure>
        <identificationNumber>GB1234</identificationNumber>
      </HolderOfTheTransitProcedure>
      <preparationDateAndTime>{UTCDateString}</preparationDateAndTime>
    </CCInvalid>

  val noDate: NodeSeq =
    <CC015C>
      <HolderOfTheTransitProcedure>
        <identificationNumber>GB1234</identificationNumber>
      </HolderOfTheTransitProcedure>
    </CC015C>

  val twoDates: NodeSeq =
    <CC015C>
      <HolderOfTheTransitProcedure>
        <identificationNumber>GB1234</identificationNumber>
      </HolderOfTheTransitProcedure>
      <preparationDateAndTime>{UTCDateString}</preparationDateAndTime>
      <preparationDateAndTime>{UTCDateString}</preparationDateAndTime>
    </CC015C>

  val badDate: NodeSeq =
    <CC015C>
      <HolderOfTheTransitProcedure>
        <identificationNumber>GB1234</identificationNumber>
      </HolderOfTheTransitProcedure>
      <preparationDateAndTime>invaliddate</preparationDateAndTime>
    </CC015C>

  val incompleteXml: String =
    "<CC015C><messageSender>GB1234</messageSender>"

  val missingInnerTag: String =
    "<CC015C><messageSender>GB1234</CC015C>"

  val mismatchedTags: String =
    "<CC015C><messageSender>GB1234</messageReceiver></CC015C>"

  "When handed an XML stream" - {
    val service = new MessagesXmlParsingServiceImpl

    "if it is valid, return an appropriate Message Data" in {
      val source = createStream(validXml)

      val result = service.extractMessageData(source)

      whenReady(result.value) {
        _ mustBe Right(MessageData(MessageType.DeclarationData, testDate))
      }
    }

    "if it doesn't have a valid message type, return ParseError.InvalidMessageType" in {
      val source = createStream(invalidMessageType)

      val result = service.extractMessageData(source)

      whenReady(result.value) {
        _ mustBe Left(ParseError.InvalidMessageType())
      }
    }

    "if it doesn't have a preparation date, return ParseError.NoElementFound" in {
      val source = createStream(noDate)

      val result = service.extractMessageData(source)

      whenReady(result.value) {
        _ mustBe Left(ParseError.NoElementFound("preparationDateAndTime"))
      }
    }

    "if it has two preparation dates, return ParseError.TooManyElementsFound" in {
      val source = createStream(twoDates)

      val result = service.extractMessageData(source)

      whenReady(result.value) {
        _ mustBe Left(ParseError.TooManyElementsFound("preparationDateAndTime"))
      }
    }

    "it it has a preparation date that is unparsable, return ParseError.BadDateTime" in {
      val stream       = createParsingEventStream(badDate)
      val parsedResult = stream.via(XmlParsers.preparationDateTimeExtractor).runWith(Sink.head)

      whenReady(parsedResult) {
        result =>
          val error = result.left.get
          error mustBe a[ParseError.BadDateTime]
          error.asInstanceOf[ParseError.BadDateTime].element mustBe "preparationDateAndTime"
          error.asInstanceOf[ParseError.BadDateTime].exception.getMessage mustBe "Text 'invaliddate' could not be parsed at index 0"
      }
    }

    "if it is missing the end tag, return ParseError.Unknown" in {
      val source = createStream(incompleteXml)

      val result = service.extractMessageData(source)

      whenReady(result.value) {
        either =>
          either mustBe a[Left[ParseError, _]]
          either.left.get mustBe a[ParseError.UnexpectedError]
          either.left.get.asInstanceOf[ParseError.UnexpectedError].caughtException.get mustBe a[IllegalStateException]
      }
    }

    "if it is missing the end of an inner tag, return ParseError.Unknown" in {
      val source = createStream(missingInnerTag)

      val result = service.extractMessageData(source)

      whenReady(result.value) {
        either =>
          either mustBe a[Left[ParseError, _]]
          either.left.get mustBe a[ParseError.UnexpectedError]
          either.left.get.asInstanceOf[ParseError.UnexpectedError].caughtException.get mustBe a[WFCException]
      }
    }

    "if it contains mismatched tags, return ParseError.Unknown" in {
      val source = createStream(mismatchedTags)

      val result = service.extractMessageData(source)

      whenReady(result.value) {
        either =>
          either mustBe a[Left[ParseError, _]]
          either.left.get mustBe a[ParseError.UnexpectedError]
          either.left.get.asInstanceOf[ParseError.UnexpectedError].caughtException.get mustBe a[WFCException]
      }
    }
  }

}
