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
import akka.stream.Materializer
import akka.stream.alpakka.xml.ParseEvent
import akka.stream.alpakka.xml.scaladsl.XmlParsing
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import uk.gov.hmrc.transitmovements.models.DeclarationData
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.services.errors.ParseError

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[DeparturesXmlParsingServiceImpl])
trait DeparturesXmlParsingService {

  def extractDeclarationData(source: Source[ByteString, _]): EitherT[Future, ParseError, DeclarationData]

}

@Singleton
class DeparturesXmlParsingServiceImpl @Inject() (implicit materializer: Materializer) extends DeparturesXmlParsingService {

  // we don't want to starve the Play pool
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  private val movementEORINumberExtractor: Flow[ParseEvent, EORINumber, NotUsed] = XmlParsing
    .subtree("CC015C" :: "messageSender" :: Nil) // TODO: see if we can get the EORI from the XSD in the future
    .collect {
      case element if element.getTextContent.nonEmpty => EORINumber(element.getTextContent)
    }

  private def declarationFlow: Flow[ByteString, DeclarationData, NotUsed] = XmlParsing.parser
    .via(movementEORINumberExtractor)
    .via(
      Flow.fromFunction(
        in => DeclarationData(in)
      )
    )

  override def extractDeclarationData(source: Source[ByteString, _]): EitherT[Future, ParseError, DeclarationData] =
    EitherT(
      source
        .via(declarationFlow)
        .fold[Either[ParseError, DeclarationData]](Left(ParseError.NoElementFound("messageSender")))(
          (current, next) =>
            current match {
              case Left(ParseError.NoElementFound(_)) => Right(next)
              case _                                  => Left(ParseError.TooManyElementsFound("messageSender"))
            }
        )
        .recover {
          case NonFatal(e) => Left(ParseError.Unknown(Some(e)))
        }
        .runWith(Sink.headOption[Either[ParseError, DeclarationData]])
        .map(
          result => result.getOrElse(Left(ParseError.NoElementFound("messageSender")))
        )
    )

}
