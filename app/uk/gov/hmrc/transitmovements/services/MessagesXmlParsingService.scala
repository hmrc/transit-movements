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
import uk.gov.hmrc.transitmovements.models.MessageData
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.services.errors.ParseError

import java.time.OffsetDateTime
import java.util.concurrent.Executors
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[MessagesXmlParsingServiceImpl])
trait MessagesXmlParsingService {
  def extractMessageData(source: Source[ByteString, _], messageType: MessageType): EitherT[Future, ParseError, MessageData]
}

@Singleton
class MessagesXmlParsingServiceImpl @Inject() (implicit materializer: Materializer) extends MessagesXmlParsingService with XmlParsingServiceHelpers {

  // we don't want to starve the Play pool
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  private def buildMessageData(mrnMaybe: ParseResult[MovementReferenceNumber], dateMaybe: ParseResult[OffsetDateTime]): ParseResult[MessageData] =
    for {
      mrn            <- mrnMaybe
      generationDate <- dateMaybe
    } yield MessageData(generationDate, Some(mrn))

  private val mrnAllocationFlow: Flow[ByteString, ParseResult[MessageData], NotUsed] =
    Flow.fromGraph(
      GraphDSL.create() {
        implicit builder =>
          import GraphDSL.Implicits._

          val broadcast = builder.add(Broadcast[ParseEvent](2))
          val combiner  = builder.add(ZipWith[ParseResult[MovementReferenceNumber], ParseResult[OffsetDateTime], ParseResult[MessageData]](buildMessageData))

          val xmlParsing      = builder.add(XmlParsing.parser)
          val messageTypeFlow = builder.add(XmlParsers.movementReferenceNumberExtractor)
          val dateFlow        = builder.add(XmlParsers.preparationDateTimeExtractor(MessageType.MrnAllocated))

          xmlParsing.out ~> broadcast.in
          broadcast.out(0) ~> messageTypeFlow ~> combiner.in0
          broadcast.out(1) ~> dateFlow ~> combiner.in1

          FlowShape(xmlParsing.in, combiner.out)
      }
    )

  private def messageFlow(messageType: MessageType): Flow[ByteString, ParseResult[MessageData], NotUsed] =
    Flow.fromGraph(
      GraphDSL.create() {
        implicit builder =>
          import GraphDSL.Implicits._

          val xmlParsing = builder.add(XmlParsing.parser)
          val dateFlow   = builder.add(XmlParsers.preparationDateTimeExtractor(messageType))
          val convertToMessageDataFlow = builder.add(Flow[ParseResult[OffsetDateTime]].map {
            _.map(
              date => MessageData(date, None)
            )
          })

          xmlParsing.out ~> dateFlow ~> convertToMessageDataFlow

          FlowShape(xmlParsing.in, convertToMessageDataFlow.out)
      }
    )

  private def extractMrnAllocationMessageData(source: Source[ByteString, _]): EitherT[Future, ParseError, MessageData] =
    EitherT(
      source
        .via(mrnAllocationFlow)
        .recover {
          case NonFatal(e) => Left(ParseError.UnexpectedError(Some(e)))
        }
        .runWith(Sink.head[Either[ParseError, MessageData]])
    )

  override def extractMessageData(source: Source[ByteString, _], messageType: MessageType): EitherT[Future, ParseError, MessageData] =
    messageType match {
      case MessageType.MrnAllocated => extractMrnAllocationMessageData(source)
      case _ =>
        EitherT(
          source
            .via(messageFlow(messageType))
            .recover {
              case NonFatal(e) => Left(ParseError.UnexpectedError(Some(e)))
            }
            .runWith(Sink.head[Either[ParseError, MessageData]])
        )
    }

}
