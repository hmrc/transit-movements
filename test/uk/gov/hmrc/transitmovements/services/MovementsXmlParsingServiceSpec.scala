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
import com.fasterxml.aalto.WFCException
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.transitmovements.base.StreamTestHelpers
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.models.ArrivalData
import uk.gov.hmrc.transitmovements.models.DeclarationData
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.LocalReferenceNumber
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.MovementType
import uk.gov.hmrc.transitmovements.services.errors.ParseError

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import scala.xml.NodeSeq

class MovementsXmlParsingServiceSpec extends AnyFreeSpec with ScalaFutures with Matchers with TestActorSystem with StreamTestHelpers {

  private val testDate      = OffsetDateTime.now(ZoneOffset.UTC)
  private val UTCDateString = testDate.toLocalDateTime.format(DateTimeFormatter.ISO_DATE_TIME)

  val validDeclarationDataXml: NodeSeq =
    <CC015C>
      <HolderOfTheTransitProcedure>
        <identificationNumber>GB1234</identificationNumber>
      </HolderOfTheTransitProcedure>
      <messageSender>token</messageSender>
      <TransitOperation>
        <LRN>12yuP9</LRN>
      </TransitOperation>
      <preparationDateAndTime>{UTCDateString}</preparationDateAndTime>
    </CC015C>

  val validArrivalDataXml: NodeSeq =
    <CC007C>
      <TraderAtDestination>
        <identificationNumber>GB1234</identificationNumber>
      </TraderAtDestination>
      <preparationDateAndTime>{UTCDateString}</preparationDateAndTime>
      <TransitOperation>
        <MRN>token</MRN>
      </TransitOperation>
    </CC007C>

  val noSenderForDeclarationData: NodeSeq =
    <CC015C>
      <messageSender>token</messageSender>
      <TransitOperation>
        <LRN>12yuP9</LRN>
      </TransitOperation>
      <preparationDateAndTime>{UTCDateString}</preparationDateAndTime>
    </CC015C>

  val noSenderForArrivalData: NodeSeq =
    <CC007C>
      <preparationDateAndTime>{UTCDateString}</preparationDateAndTime>
      <TransitOperation>
        <MRN>token</MRN>
      </TransitOperation>
    </CC007C>

  val twoSenders: NodeSeq =
    <CC015C>
      <HolderOfTheTransitProcedure>
        <identificationNumber>GB1234</identificationNumber>
        <identificationNumber>XI1234</identificationNumber>
      </HolderOfTheTransitProcedure>
      <preparationDateAndTime>{UTCDateString}</preparationDateAndTime>
    </CC015C>

  val noDateForDeclarationData: NodeSeq =
    <CC015C>
      <HolderOfTheTransitProcedure>
        <identificationNumber>GB1234</identificationNumber>
      </HolderOfTheTransitProcedure>
    </CC015C>

  val noDateForArrivalData: NodeSeq =
    <CC007C>
      <TraderAtDestination>
        <identificationNumber>GB1234</identificationNumber>
      </TraderAtDestination>
      <TransitOperation>
        <MRN>token</MRN>
      </TransitOperation>
    </CC007C>

  val noMrnForArrivalData: NodeSeq =
    <CC007C>
      <TraderAtDestination>
        <identificationNumber>GB1234</identificationNumber>
      </TraderAtDestination>
      <preparationDateAndTime>{UTCDateString}</preparationDateAndTime>
      <TransitOperation>
      </TransitOperation>
    </CC007C>

  val twoDates: NodeSeq =
    <CC015C>
      <HolderOfTheTransitProcedure>
        <identificationNumber>GB1234</identificationNumber>
      </HolderOfTheTransitProcedure>
      <preparationDateAndTime>{UTCDateString}</preparationDateAndTime>
      <preparationDateAndTime>{UTCDateString}</preparationDateAndTime>
    </CC015C>

  val badDateDeclarationData: NodeSeq =
    <CC015C>
      <HolderOfTheTransitProcedure>
        <identificationNumber>GB1234</identificationNumber>
      </HolderOfTheTransitProcedure>
      <preparationDateAndTime>notadate</preparationDateAndTime>
    </CC015C>

  val badDateArrivalData: NodeSeq =
    <CC007C>
      <TraderAtDestination>
        <identificationNumber>GB1234</identificationNumber>
      </TraderAtDestination>
      <preparationDateAndTime>notadate</preparationDateAndTime>
      <TransitOperation>
        <MRN>token</MRN>
      </TransitOperation>
    </CC007C>

  val incompleteDeclarationDataXml: String =
    "<CC015C><messageSender>GB1234</messageSender>"

  val incompleteArrivalDataXml: String =
    "<CC007C><messageSender>GB1234</messageSender>"

  val missingInnerTag: String =
    "<CC015C><messageSender>GB1234</CC015C>"

  val mismatchedTags: String =
    "<CC015C><messageSender>GB1234</messageReceiver></CC015C>"

  "When calling extract" - {
    val service = new MovementsXmlParsingServiceImpl

    "indicating we have a departure will return a Declaration Data" in {
      val source = createStream(validDeclarationDataXml)

      val result = service.extractData(MovementType.Departure, source)

      whenReady(result.value) {
        _ mustBe Right(DeclarationData(Some(EORINumber("GB1234")), testDate, LocalReferenceNumber("12yuP9")))
      }
    }

    "indicating we have an arrival will return a Arrival Data" in {
      val source = createStream(validArrivalDataXml)

      val result = service.extractData(MovementType.Arrival, source)

      whenReady(result.value) {
        _ mustBe Right(ArrivalData(Some(EORINumber("GB1234")), testDate, MovementReferenceNumber("token")))
      }
    }
  }

  "When handed an Declaration Data XML stream" - {
    val service = new MovementsXmlParsingServiceImpl

    "if it is valid, return an appropriate Declaration Data" in {
      val source = createStream(validDeclarationDataXml)

      val result = service.extractDeclarationData(source)

      whenReady(result.value) {
        _ mustBe Right(DeclarationData(Some(EORINumber("GB1234")), testDate, LocalReferenceNumber("12yuP9")))
      }
    }

    "if it doesn't have a identificationNumber, return Right of DeclarationData with movementEoriNumber set to None" in {
      val source = createStream(noSenderForDeclarationData)

      val result = service.extractDeclarationData(source)

      whenReady(result.value) {
        _ mustBe Right(DeclarationData(None, testDate, LocalReferenceNumber("12yuP9")))
      }
    }

    "if it doesn't have a preparation date, return ParseError.NoElementFound" in {
      val source = createStream(noDateForDeclarationData)

      val result = service.extractDeclarationData(source)

      whenReady(result.value) {
        _ mustBe Left(ParseError.NoElementFound("preparationDateAndTime"))
      }
    }

    "if it has two identificationNumbers, return ParseError.TooManyElementsFound" in {
      val source = createStream(twoSenders)

      val result = service.extractDeclarationData(source)

      whenReady(result.value) {
        _ mustBe Left(ParseError.TooManyElementsFound("identificationNumber"))
      }
    }

    "if it has two preparation dates, return ParseError.TooManyElementsFound" in {
      val source = createStream(twoDates)

      val result = service.extractDeclarationData(source)

      whenReady(result.value) {
        _ mustBe Left(ParseError.TooManyElementsFound("preparationDateAndTime"))
      }
    }

    "it it has a preparation date that is unparsable, return ParseError.BadDateTime" in {
      val stream       = createParsingEventStream(badDateDeclarationData)
      val parsedResult = stream.via(XmlParsers.preparationDateTimeExtractor(MessageType.DeclarationData)).runWith(Sink.head)

      whenReady(parsedResult) {
        result =>
          val error = result.left.get
          error mustBe a[ParseError.BadDateTime]
          error.asInstanceOf[ParseError.BadDateTime].element mustBe "preparationDateAndTime"
          error.asInstanceOf[ParseError.BadDateTime].exception.getMessage mustBe "Text 'notadate' could not be parsed at index 0"
      }
    }

    "if it is missing the end tag, return ParseError.Unknown" in {
      val source = createStream(incompleteDeclarationDataXml)

      val result = service.extractDeclarationData(source)

      whenReady(result.value) {
        either =>
          either mustBe a[Left[ParseError, _]]
          either.left.get mustBe a[ParseError.UnexpectedError]
          either.left.get.asInstanceOf[ParseError.UnexpectedError].caughtException.get mustBe a[IllegalStateException]
      }
    }

    "if it is missing the end of an inner tag, return ParseError.Unknown" in {
      val source = createStream(missingInnerTag)

      val result = service.extractDeclarationData(source)

      whenReady(result.value) {
        either =>
          either mustBe a[Left[ParseError, _]]
          either.left.get mustBe a[ParseError.UnexpectedError]
          either.left.get.asInstanceOf[ParseError.UnexpectedError].caughtException.get mustBe a[WFCException]
      }
    }

    "if it contains mismatched tags, return ParseError.Unknown" in {
      val source = createStream(mismatchedTags)

      val result = service.extractDeclarationData(source)

      whenReady(result.value) {
        either =>
          either mustBe a[Left[ParseError, _]]
          either.left.get mustBe a[ParseError.UnexpectedError]
          either.left.get.asInstanceOf[ParseError.UnexpectedError].caughtException.get mustBe a[WFCException]
      }
    }
  }

  "When handed an Arrival Data XML stream" - {
    val service = new MovementsXmlParsingServiceImpl

    "if it is valid, return an appropriate Arrival Data" in {
      val source = createStream(validArrivalDataXml)

      val result = service.extractArrivalData(source)

      whenReady(result.value) {
        _ mustBe Right(ArrivalData(Some(EORINumber("GB1234")), testDate, MovementReferenceNumber("token")))
      }
    }

    "if it doesn't have a identificationNumber, return Right of ArrivalData with movementEoriNumber set to None" in {
      val source = createStream(noSenderForArrivalData)

      val result = service.extractArrivalData(source)

      whenReady(result.value) {
        _ mustBe Right(ArrivalData(None, testDate, MovementReferenceNumber("token")))
      }
    }

    "if it doesn't have a preparation date, return ParseError.NoElementFound" in {
      val source = createStream(noDateForArrivalData)

      val result = service.extractArrivalData(source)

      whenReady(result.value) {
        _ mustBe Left(ParseError.NoElementFound("preparationDateAndTime"))
      }
    }

    "it it has a preparation date that is unparsable, return ParseError.BadDateTime" in {
      val stream       = createParsingEventStream(badDateArrivalData)
      val parsedResult = stream.via(XmlParsers.preparationDateTimeExtractor(MessageType.ArrivalNotification)).runWith(Sink.head)

      whenReady(parsedResult) {
        result =>
          val error = result.left.get
          error mustBe a[ParseError.BadDateTime]
          error.asInstanceOf[ParseError.BadDateTime].element mustBe "preparationDateAndTime"
          error.asInstanceOf[ParseError.BadDateTime].exception.getMessage mustBe "Text 'notadate' could not be parsed at index 0"
      }
    }

    "if it is missing the end tag, return ParseError.Unknown" in {
      val source = createStream(incompleteArrivalDataXml)

      val result = service.extractArrivalData(source)

      whenReady(result.value) {
        either =>
          either mustBe a[Left[ParseError, _]]
          either.left.get mustBe a[ParseError.UnexpectedError]
          either.left.get.asInstanceOf[ParseError.UnexpectedError].caughtException.get mustBe a[IllegalStateException]
      }
    }

    "if it doesn't have a mrn, return ParseError.NoElementFound" in {
      val source = createStream(noMrnForArrivalData)

      val result = service.extractArrivalData(source)

      whenReady(result.value) {
        _ mustBe Left(ParseError.NoElementFound("MRN"))
      }
    }

  }

}
