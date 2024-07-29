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

import org.apache.pekko.stream.FlowShape
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.connectors.xml.ParseEvent
import org.apache.pekko.stream.connectors.xml.scaladsl.XmlParsing
import org.apache.pekko.stream.scaladsl.Broadcast
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.GraphDSL
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.scaladsl.ZipWith
import org.apache.pekko.util.ByteString
import cats.data.EitherT
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import uk.gov.hmrc.transitmovements.models.MessageData
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MessageType.MrnAllocated
import uk.gov.hmrc.transitmovements.models.requests.common.MovementReferenceNumber
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

  def buildMessageData(
    dateMaybe: ParseResult[OffsetDateTime],
    mrnMaybe: ParseResult[Option[MovementReferenceNumber]]
  ): ParseResult[MessageData] =
    for {
      generationDate <- dateMaybe
      mrn            <- mrnMaybe
    } yield MessageData(generationDate, mrn)

  def messageFlow(messageType: MessageType) = Flow.fromGraph(
    GraphDSL.create() {
      implicit builder =>
        import GraphDSL.Implicits._

        val broadcastXml = builder.add(Broadcast[ParseEvent](2))
        val combiner =
          builder.add(
            ZipWith[ParseResult[OffsetDateTime], ParseResult[Option[MovementReferenceNumber]], ParseResult[MessageData]](
              (date, mrn) => buildMessageData(date, mrn)
            )
          )

        val xmlParsing = builder.add(XmlParsing.parser)
        val dateFlow   = builder.add(XmlParsers.preparationDateTimeExtractor(messageType))

        xmlParsing.out ~> broadcastXml.in

        broadcastXml.out(0) ~> dateFlow ~> combiner.in0

        if (messageType == MrnAllocated) {
          val mrnFlow = builder.add(XmlParsers.movementReferenceNumberExtractor("CC028C"))
          val toOptionFlow = builder.add(Flow[ParseResult[MovementReferenceNumber]].map[ParseResult[Option[MovementReferenceNumber]]] {
            case Right(mrn) => Right(Some(mrn))
            case Left(x)    => Left(x)
          })

          broadcastXml.out(1) ~> mrnFlow ~> toOptionFlow ~> combiner.in1
        } else {
          broadcastXml.out(1) ~> Sink.ignore
          Source.single(Right(None)) ~> combiner.in1
        }

        FlowShape(xmlParsing.in, combiner.out)
    }
  )

  override def extractMessageData(source: Source[ByteString, _], messageType: MessageType): EitherT[Future, ParseError, MessageData] =
    EitherT(
      source
        .via(messageFlow(messageType))
        .recover {
          case NonFatal(e) => Left(ParseError.UnexpectedError(Some(e)))
        }
        .runWith(Sink.head[Either[ParseError, MessageData]])
    )

}
