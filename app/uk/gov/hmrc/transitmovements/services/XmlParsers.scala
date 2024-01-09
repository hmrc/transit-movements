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

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.connectors.xml.ParseEvent
import org.apache.pekko.stream.connectors.xml.scaladsl.XmlParsing
import org.apache.pekko.stream.scaladsl.Flow
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.LocalReferenceNumber
import uk.gov.hmrc.transitmovements.models.MessageSender
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.services.errors.ParseError

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

object XmlParsers extends XmlParsingServiceHelpers {

  def movementEORINumberExtractor(rootNode: String, eori: String): Flow[ParseEvent, ParseResult[Option[EORINumber]], NotUsed] =
    XmlParsing
      .subtree(rootNode :: eori :: "identificationNumber" :: Nil)
      .collect {
        case element if element.getTextContent.nonEmpty => EORINumber(element.getTextContent)
      }
      .singleOption("identificationNumber")

  def preparationDateTimeExtractor(messageType: MessageType): Flow[ParseEvent, ParseResult[OffsetDateTime], NotUsed] = XmlParsing
    .subtree(messageType.rootNode :: "preparationDateAndTime" :: Nil)
    .collect {
      case element if element.getTextContent.nonEmpty =>
        LocalDateTime.parse(element.getTextContent).atOffset(ZoneOffset.UTC)
    }
    .single("preparationDateAndTime")
    .recover {
      case exception: DateTimeParseException => Left(ParseError.BadDateTime("preparationDateAndTime", exception))
    }

  def movementReferenceNumberExtractor(rootNode: String): Flow[ParseEvent, ParseResult[MovementReferenceNumber], NotUsed] = XmlParsing
    .subtree(rootNode :: "TransitOperation" :: "MRN" :: Nil)
    .collect {
      case element if element.getTextContent.nonEmpty => MovementReferenceNumber(element.getTextContent)
    }
    .single("MRN")

  def movementLRNExtractor(rootNode: String): Flow[ParseEvent, ParseResult[LocalReferenceNumber], NotUsed] =
    XmlParsing
      .subtree(rootNode :: "TransitOperation" :: "LRN" :: Nil)
      .collect {
        case element if element.getTextContent.nonEmpty => LocalReferenceNumber(element.getTextContent)
      }
      .single("LRN")

  def movementMessageSenderExtractor(rootNode: String): Flow[ParseEvent, ParseResult[MessageSender], NotUsed] =
    XmlParsing
      .subtree(rootNode :: "messageSender" :: Nil)
      .collect {
        case element if element.getTextContent.nonEmpty => MessageSender(element.getTextContent)
      }
      .single("messageSender")

}
