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

import akka.NotUsed
import akka.stream.alpakka.xml.ParseEvent
import akka.stream.alpakka.xml.StartElement
import akka.stream.alpakka.xml.scaladsl.XmlParsing
import akka.stream.scaladsl.Flow
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.services.errors.ParseError

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

object XmlParsers extends XmlParsingServiceHelpers {

  val movementEORINumberExtractor: Flow[ParseEvent, ParseResult[EORINumber], NotUsed] = XmlParsing
    .subtree("CC015C" :: "HolderOfTheTransitProcedure" :: "identificationNumber" :: Nil)
    .collect {
      case element if element.getTextContent.nonEmpty => EORINumber(element.getTextContent)
    }
    .single("identificationNumber")

  val preparationDateTimeExtractor: Flow[ParseEvent, ParseResult[OffsetDateTime], NotUsed] = XmlParsing
    .subtree("CC015C" :: "preparationDateAndTime" :: Nil)
    .collect {
      case element if element.getTextContent.nonEmpty =>
        LocalDateTime.parse(element.getTextContent).atOffset(ZoneOffset.UTC)
    }
    .single("preparationDateAndTime")
    .recover {
      case exception: DateTimeParseException => Left(ParseError.BadDateTime("preparationDateAndTime", exception))
    }

  val messageTypeExtractor: Flow[ParseEvent, ParseResult[MessageType], NotUsed] = Flow[ParseEvent]
    .mapConcat {
      case s: StartElement if MessageType.values.exists(_.rootNode == s.localName) =>
        Seq(MessageType.values.find(_.rootNode == s.localName).get)
      case _ => Seq.empty
    }
    .take(1)
    .fold[Either[ParseError, MessageType]](Left(ParseError.InvalidMessageType()))(
      (_, next) => Right(next)
    )
}
