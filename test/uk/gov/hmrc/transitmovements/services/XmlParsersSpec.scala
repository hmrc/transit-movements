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

package uk.gov.hmrc.transitmovements.services

import akka.stream.scaladsl.Sink
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import uk.gov.hmrc.transitmovements.base.StreamTestHelpers
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.services.errors.ParseError

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import scala.xml.NodeSeq

class XmlParsersSpec extends AnyFreeSpec with TestActorSystem with Matchers with StreamTestHelpers with ScalaFutures with ScalaCheckPropertyChecks {

  "EORINumber parser" - {

    val withEntry: NodeSeq =
      <CC015C>
        <HolderOfTheTransitProcedure>
          <identificationNumber>GB1234</identificationNumber>
        </HolderOfTheTransitProcedure>
      </CC015C>

    val withNoEntry: NodeSeq =
      <CC015C>
      </CC015C>

    val withTwoEntries: NodeSeq =
      <CC015C>
        <HolderOfTheTransitProcedure>
          <identificationNumber>GB1234</identificationNumber>
          <identificationNumber>XI1234</identificationNumber>
        </HolderOfTheTransitProcedure>
      </CC015C>

    "when provided with a valid entry" in {
      val stream       = createParsingEventStream(withEntry)
      val parsedResult = stream.via(XmlParsers.movementEORINumberExtractor("CC015C", "HolderOfTheTransitProcedure")).runWith(Sink.head)

      whenReady(parsedResult) {
        _ mustBe Right(Some(EORINumber("GB1234")))
      }
    }

    "when provided with no entry" in {
      val stream       = createParsingEventStream(withNoEntry)
      val parsedResult = stream.via(XmlParsers.movementEORINumberExtractor("CC015C", "HolderOfTheTransitProcedure")).runWith(Sink.head)

      whenReady(parsedResult) {
        _ mustBe Right(None)
      }
    }

    "when provided with two entries" in {
      val stream       = createParsingEventStream(withTwoEntries)
      val parsedResult = stream.via(XmlParsers.movementEORINumberExtractor("CC015C", "HolderOfTheTransitProcedure")).runWith(Sink.head)

      whenReady(parsedResult) {
        _ mustBe Left(ParseError.TooManyElementsFound("identificationNumber"))
      }
    }

  }

  "Preparation Date and Time parser" - {
    val dateTime          = OffsetDateTime.now(ZoneOffset.UTC)
    val formattedDateTime = dateTime.toLocalDateTime.format(DateTimeFormatter.ISO_DATE_TIME)

    val withEntry: NodeSeq =
      <CC015C>
        <preparationDateAndTime>{formattedDateTime}</preparationDateAndTime>
      </CC015C>

    val withDateParseError: NodeSeq =
      <CC015C>
        <preparationDateAndTime>notadatetime</preparationDateAndTime>
      </CC015C>

    val withNoEntry: NodeSeq =
      <CC015C>
      </CC015C>

    val withTwoEntries: NodeSeq =
      <CC015C>
        <preparationDateAndTime>{formattedDateTime}</preparationDateAndTime>
        <preparationDateAndTime>{formattedDateTime}</preparationDateAndTime>
      </CC015C>

    "when provided with a valid entry" in {
      val stream       = createParsingEventStream(withEntry)
      val parsedResult = stream.via(XmlParsers.preparationDateTimeExtractor(MessageType.DeclarationData)).runWith(Sink.head)

      whenReady(parsedResult) {
        _ mustBe Right(dateTime)
      }
    }

    "when provided with no entry" in {
      val stream       = createParsingEventStream(withNoEntry)
      val parsedResult = stream.via(XmlParsers.preparationDateTimeExtractor(MessageType.DeclarationData)).runWith(Sink.head)

      whenReady(parsedResult) {
        _ mustBe Left(ParseError.NoElementFound("preparationDateAndTime"))
      }
    }

    "when provided with two entries" in {
      val stream       = createParsingEventStream(withTwoEntries)
      val parsedResult = stream.via(XmlParsers.preparationDateTimeExtractor(MessageType.DeclarationData)).runWith(Sink.head)

      whenReady(parsedResult) {
        _ mustBe Left(ParseError.TooManyElementsFound("preparationDateAndTime"))
      }
    }

    "when provided with an unparsable entry" in {
      val stream       = createParsingEventStream(withDateParseError)
      val parsedResult = stream.via(XmlParsers.preparationDateTimeExtractor(MessageType.DeclarationData)).runWith(Sink.head)

      whenReady(parsedResult) {
        result =>
          val error = result.left.get
          error mustBe a[ParseError.BadDateTime]
          error.asInstanceOf[ParseError.BadDateTime].element mustBe "preparationDateAndTime"
          error.asInstanceOf[ParseError.BadDateTime].exception.getMessage mustBe "Text 'notadatetime' could not be parsed at index 0"
      }
    }
  }

  "Movement Reference Number parser" - {

    val withEntry: NodeSeq =
      <CC007C>
        <TransitOperation>
          <MRN>movement reference number</MRN>
        </TransitOperation>
      </CC007C>

    val withNoEntry: NodeSeq =
      <CC007C>
      </CC007C>

    val withTwoEntries: NodeSeq =
      <CC007C>
        <TransitOperation>
          <MRN>movement reference number1</MRN>
          <MRN>movement reference number2</MRN>
        </TransitOperation>
      </CC007C>

    "when provided with a valid entry" in {
      val stream       = createParsingEventStream(withEntry)
      val parsedResult = stream.via(XmlParsers.movementReferenceNumberExtractor("CC007C")).runWith(Sink.head)

      whenReady(parsedResult) {
        _ mustBe Right(MovementReferenceNumber("movement reference number"))
      }
    }

    "when provided with no entry" in {
      val stream       = createParsingEventStream(withNoEntry)
      val parsedResult = stream.via(XmlParsers.movementReferenceNumberExtractor("CC007C")).runWith(Sink.head)

      whenReady(parsedResult) {
        _ mustBe Left(ParseError.NoElementFound("MRN"))
      }
    }

    "when provided with two entries" in {
      val stream       = createParsingEventStream(withTwoEntries)
      val parsedResult = stream.via(XmlParsers.movementReferenceNumberExtractor("CC007C")).runWith(Sink.head)

      whenReady(parsedResult) {
        _ mustBe Left(ParseError.TooManyElementsFound("MRN"))
      }
    }

  }

}
