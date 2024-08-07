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

import org.apache.pekko.stream.scaladsl.Sink
import com.fasterxml.aalto.WFCException
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import uk.gov.hmrc.transitmovements.base.StreamTestHelpers
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.models.MessageData
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.services.errors.ParseError

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import scala.xml.NodeSeq

class MessageXmlParsingServiceSpec extends AnyFreeSpec with ScalaFutures with Matchers with TestActorSystem with StreamTestHelpers {

  private val testDate      = OffsetDateTime.now(ZoneOffset.UTC)
  private val UTCDateString = testDate.toLocalDateTime.format(DateTimeFormatter.ISO_DATE_TIME)

  implicit val defaultPatience: PatienceConfig =
    PatienceConfig(timeout = Span(6, Seconds))

  val validMrnAllocationXml: NodeSeq =
    <CC028C>
      <TransitOperation>
        <identificationNumber>GB1234</identificationNumber>
        <MRN>MRN123</MRN>
      </TransitOperation>
      <preparationDateAndTime>{UTCDateString}</preparationDateAndTime>
    </CC028C>

  def noDate(messageType: String, isMrnAllocated: Boolean): String =
    if (!isMrnAllocated)
      s"<$messageType><HolderOfTheTransitProcedure><identificationNumber>GB1234</identificationNumber></HolderOfTheTransitProcedure></$messageType>"
    else s"<$messageType><TransitOperation><MRN>MRN12345</MRN></TransitOperation></$messageType>"

  def twoDates(messageType: String, isMrnAllocated: Boolean): String =
    if (!isMrnAllocated)
      s"<$messageType><HolderOfTheTransitProcedure><identificationNumber>GB1234</identificationNumber></HolderOfTheTransitProcedure><preparationDateAndTime>$UTCDateString</preparationDateAndTime><preparationDateAndTime>$UTCDateString</preparationDateAndTime></$messageType>"
    else
      s"<$messageType><TransitOperation><MRN>MRN12345</MRN></TransitOperation><preparationDateAndTime>$UTCDateString</preparationDateAndTime><preparationDateAndTime>$UTCDateString</preparationDateAndTime></$messageType>"

  def badDate(messageType: String, isMrnAllocated: Boolean): String =
    if (!isMrnAllocated)
      s"<$messageType><HolderOfTheTransitProcedure><identificationNumber>GB1234</identificationNumber></HolderOfTheTransitProcedure><preparationDateAndTime>invaliddate</preparationDateAndTime></$messageType>"
    else s"<$messageType><TransitOperation><MRN>MRN12345</MRN></TransitOperation><preparationDateAndTime>invaliddate</preparationDateAndTime></$messageType>"

  val incompleteXml: String =
    "<CC015C><messageSender>GB1234</messageSender>"

  val missingInnerTag: String =
    "<CC015C><messageSender>GB1234</CC015C>"

  val mismatchedTags: String =
    "<CC015C><messageSender>GB1234</messageReceiver></CC015C>"

  "When handed an XML stream" - {
    val service = new MessagesXmlParsingServiceImpl

    "if it is valid and not MrnAllocated, return an appropriate Message Data" in {
      val validXml: NodeSeq =
        <CC029C>
          <HolderOfTheTransitProcedure>
            <identificationNumber>GB1234</identificationNumber>
          </HolderOfTheTransitProcedure>
          <preparationDateAndTime>{UTCDateString}</preparationDateAndTime>
        </CC029C>

      val source = createStream(validXml)

      val result = service.extractMessageData(source, MessageType.ReleaseForTransit)

      whenReady(result.value) {
        _ mustBe Right(MessageData(testDate, None))
      }
    }

    val notMrnMessageType = Gen.oneOf(MessageType.values.filterNot(_ == MessageType.MrnAllocated)).sample.get

    Seq(MessageType.MrnAllocated, notMrnMessageType).foreach {
      messageType =>
        val isMrnAllocated         = messageType == MessageType.MrnAllocated
        val messageTypeDescription = if (isMrnAllocated) "is MrnAllocated" else "is not MrnAllocated"
        val rootNode               = messageType.rootNode

        s"$messageTypeDescription, if it doesn't have a preparation date, return ParseError.NoElementFound" in {
          val noDateXml = xml.XML.loadString(noDate(rootNode, isMrnAllocated))

          val source = createStream(noDateXml)

          val result = service.extractMessageData(source, messageType)

          whenReady(result.value) {
            _ mustBe Left(ParseError.NoElementFound("preparationDateAndTime"))
          }
        }

        s"$messageTypeDescription, if it has two preparation dates, return ParseError.TooManyElementsFound" in {
          val twoDatesXml = xml.XML.loadString(twoDates(rootNode, isMrnAllocated))

          val source = createStream(twoDatesXml)

          val result = service.extractMessageData(source, messageType)

          whenReady(result.value) {
            _ mustBe Left(ParseError.TooManyElementsFound("preparationDateAndTime"))
          }
        }

        s"$messageTypeDescription, if it has a preparation date that is unparsable, return ParseError.BadDateTime" in {
          val badDateXml   = xml.XML.loadString(badDate(rootNode, isMrnAllocated))
          val stream       = createParsingEventStream(badDateXml)
          val parsedResult = stream.via(XmlParsers.preparationDateTimeExtractor(messageType)).runWith(Sink.head)

          whenReady(parsedResult) {
            case Left(x: ParseError.BadDateTime) =>
              x.element mustBe "preparationDateAndTime"
              x.exception.getMessage mustBe "Text 'invaliddate' could not be parsed at index 0"
            case _ => fail("Did not get a ParseError.BadDateTime")
          }
        }

        s"$messageTypeDescription, if it is missing the end tag, return ParseError.UnexpectedError" in {
          val source = createStream(incompleteXml)

          val result = service.extractMessageData(source, messageType)

          whenReady(result.value) {
            case Left(ParseError.UnexpectedError(Some(_: IllegalStateException))) => succeed
            case _                                                                => fail("Did not get an IllegalStateException")
          }
        }

        s"$messageTypeDescription, if it is missing the end of an inner tag, return ParseError.UnexpectedError" in {
          val source = createStream(missingInnerTag)

          val result = service.extractMessageData(source, messageType)

          whenReady(result.value) {
            case Left(ParseError.UnexpectedError(Some(_: WFCException))) => succeed
            case _                                                       => fail("Did not get an WCFException")
          }
        }

        s"$messageTypeDescription, if it contains mismatched tags, return ParseError.UnexpectedError" in {
          val source = createStream(mismatchedTags)

          val result = service.extractMessageData(source, messageType)

          whenReady(result.value) {
            case Left(ParseError.UnexpectedError(Some(_: WFCException))) => succeed
            case _                                                       => fail("Did not get an WCFException")
          }
        }
    }

    "if the message type is MrnAllocated, return an appropriate Message Data" in {

      val source = createStream(validMrnAllocationXml)

      val result = service.extractMessageData(source, MessageType.MrnAllocated)

      whenReady(result.value) {
        _ mustBe Right(MessageData(testDate, Some(MovementReferenceNumber("MRN123"))))
      }
    }

  }

}
