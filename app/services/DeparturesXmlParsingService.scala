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

package services

import akka.actor.ActorSystem
import akka.stream.SinkShape
import akka.stream.alpakka.xml.ParseEvent
import akka.stream.alpakka.xml.scaladsl.XmlParsing
import akka.stream.scaladsl.Broadcast
import akka.stream.scaladsl.GraphDSL
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.OptionT
import cats.syntax.all._
import com.google.inject.ImplementedBy
import models.MessageType
import models.errors.BadRequestError
import models.request.DeclarationDataRequest
import models.request.DepartureMessageRequest
import models.values.CustomsOfficeNumber
import models.values.EoriNumber
import models.values.LocalReferenceNumber

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[DeparturesXmlParsingServiceImpl])
trait DeparturesXmlParsingService {
  def parseDeclarationDataRequest(
    eoriNumber: EoriNumber,
    request: Source[ByteString, _]
  ): Future[Either[BadRequestError, DeclarationDataRequest]]

  def parseDepartureMessageRequest(
    eoriNumber: EoriNumber,
    messageType: MessageType,
    request: Source[ByteString, _]
  ): Future[Either[BadRequestError, DepartureMessageRequest]]
}

@Singleton
class DeparturesXmlParsingServiceImpl @Inject() ()(implicit system: ActorSystem)
  extends DeparturesXmlParsingService {

  implicit val ec: ExecutionContext = system.dispatcher

  case class DeclarationDataItems(
    preparationDateTime: OffsetDateTime,
    localReferenceNumber: LocalReferenceNumber,
    officeOfDeparture: CustomsOfficeNumber
  )

  val preparationDateTimeSink  = Sink.headOption[OffsetDateTime]
  val localReferenceNumberSink = Sink.headOption[LocalReferenceNumber]
  val officeOfDepartureSink    = Sink.headOption[CustomsOfficeNumber]

  def buildDeclarationDataItems(
    preparationDateTime: Future[Option[OffsetDateTime]],
    localReferenceNumber: Future[Option[LocalReferenceNumber]],
    officeOfDeparture: Future[Option[CustomsOfficeNumber]]
  ) = (for {
    dateTime <- OptionT(preparationDateTime)
    lrn      <- OptionT(localReferenceNumber)
    office   <- OptionT(officeOfDeparture)
  } yield DeclarationDataItems(dateTime, lrn, office)).value

  val preparationDateTimeFlow = XmlParsing
    .subtree("CC015C" :: "preparationDateAndTime" :: Nil)
    .collect {
      case element if element.getTextContent.nonEmpty =>
        LocalDateTime.parse(element.getTextContent).atOffset(ZoneOffset.UTC)
    }

  val localReferenceNumberFlow = XmlParsing
    .subtree("CC015C" :: "TransitOperation" :: "LRN" :: Nil)
    .collect {
      case element if element.getTextContent.nonEmpty =>
        LocalReferenceNumber(element.getTextContent)
    }

  val officeOfDepartureFlow = XmlParsing
    .subtree("CC015C" :: "CustomsOfficeOfDeparture" :: "referenceNumber" :: Nil)
    .collect {
      case element if element.getTextContent.nonEmpty =>
        CustomsOfficeNumber(element.getTextContent)
    }

  val declarationDataSink = XmlParsing.parser.toMat(
    Sink.fromGraph(
      GraphDSL.create(preparationDateTimeSink, localReferenceNumberSink, officeOfDepartureSink)(
        buildDeclarationDataItems
      ) { implicit builder => (prepDateTimeSink, lrnSink, officeSink) =>
        import GraphDSL.Implicits._

        val broadcast = builder.add(Broadcast[ParseEvent](3))

        val preparationDateTime  = builder.add(preparationDateTimeFlow)
        val localReferenceNumber = builder.add(localReferenceNumberFlow)
        val officeOfDeparture    = builder.add(officeOfDepartureFlow)

        broadcast.out(0) ~> preparationDateTime ~> prepDateTimeSink.in
        broadcast.out(1) ~> localReferenceNumber ~> lrnSink.in
        broadcast.out(2) ~> officeOfDeparture ~> officeSink.in

        SinkShape(broadcast.in)
      }
    )
  )(Keep.right)

  override def parseDeclarationDataRequest(
    eoriNumber: EoriNumber,
    request: Source[ByteString, _]
  ): Future[Either[BadRequestError, DeclarationDataRequest]] = {
    request.runWith(declarationDataSink).map { maybeData =>
      Either
        .fromOption(
          maybeData,
          BadRequestError(
            s"Unable to parse required values from ${MessageType.DeclarationData.code} message"
          )
        )
        .map { data =>
          DeclarationDataRequest(
            eoriNumber,
            data.preparationDateTime,
            data.localReferenceNumber,
            data.officeOfDeparture
          )
        }
    }

  }

  override def parseDepartureMessageRequest(
    eoriNumber: EoriNumber,
    messageType: MessageType,
    request: Source[ByteString, _]
  ): Future[Either[BadRequestError, DepartureMessageRequest]] = ???

}
