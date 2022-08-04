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
import akka.stream.FlowShape
import akka.stream.Materializer
import akka.stream.alpakka.xml.ParseEvent
import akka.stream.alpakka.xml.scaladsl.XmlParsing
import akka.stream.scaladsl.Broadcast
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.GraphDSL
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.ZipWith
import akka.util.ByteString
import cats.data.EitherT
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import uk.gov.hmrc.transitmovements.models.DeclarationData
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.services.errors.ParseError

import java.time.OffsetDateTime
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[DeparturesXmlParsingServiceImpl])
trait DeparturesXmlParsingService {

  def extractDeclarationData(source: Source[ByteString, _]): EitherT[Future, ParseError, DeclarationData]

}

@Singleton
class DeparturesXmlParsingServiceImpl @Inject() (implicit materializer: Materializer) extends DeparturesXmlParsingService with XmlParsingServiceHelpers {

  // we don't want to starve the Play pool
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  private def buildDeclarationData(eoriMaybe: ParseResult[EORINumber], dateMaybe: ParseResult[OffsetDateTime]): ParseResult[DeclarationData] =
    for {
      eoriNumber     <- eoriMaybe
      generationDate <- dateMaybe
    } yield DeclarationData(eoriNumber, generationDate)

  private val declarationFlow: Flow[ByteString, ParseResult[DeclarationData], NotUsed] =
    Flow.fromGraph(
      GraphDSL.create() {
        implicit builder =>
          import GraphDSL.Implicits._

          val broadcast = builder.add(Broadcast[ParseEvent](2))
          val combiner  = builder.add(ZipWith[ParseResult[EORINumber], ParseResult[OffsetDateTime], ParseResult[DeclarationData]](buildDeclarationData))

          val xmlParsing = builder.add(XmlParsing.parser)
          val eoriFlow   = builder.add(XmlParsers.movementEORINumberExtractor)
          val dateFlow   = builder.add(XmlParsers.preparationDateTimeExtractor)

          xmlParsing.out ~> broadcast.in
          broadcast.out(0) ~> eoriFlow ~> combiner.in0
          broadcast.out(1) ~> dateFlow ~> combiner.in1

          FlowShape(xmlParsing.in, combiner.out)
      }
    )

  override def extractDeclarationData(source: Source[ByteString, _]): EitherT[Future, ParseError, DeclarationData] =
    EitherT(
      source
        .via(declarationFlow)
        .recover {
          case NonFatal(e) => Left(ParseError.UnexpectedError(Some(e)))
        }
        .runWith(Sink.head[Either[ParseError, DeclarationData]])
    )

}
