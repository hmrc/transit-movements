/*
 * Copyright 2024 HM Revenue & Customs
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

import cats.data.EitherT
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import play.api.mvc.Request
import uk.gov.hmrc.transitmovements.controllers.errors.PresentationError

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[SourceManagementServiceImpl])
trait SourceManagementService {
  def replicateRequestSource(request: Request[Source[ByteString, ?]], count: Int): EitherT[Future, PresentationError, List[Source[ByteString, ?]]]
  def replicateSource(source: Source[ByteString, ?], count: Int): EitherT[Future, PresentationError, List[Source[ByteString, ?]]]
  def calculateSize(source: Source[ByteString, ?]): EitherT[Future, PresentationError, Long]
}

class SourceManagementServiceImpl @Inject() (implicit materializer: Materializer, executionContext: ExecutionContext) extends SourceManagementService {

  private def createReusableSource(seq: Seq[ByteString]): Source[ByteString, ?] = Source(seq.toList)

  def replicateSource(source: Source[ByteString, ?], count: Int): EitherT[Future, PresentationError, List[Source[ByteString, ?]]] =
    for {
      byteStringSeq <- materializeSource(source)
      reusableSource = createReusableSource(byteStringSeq)
    } yield List.fill(count)(reusableSource)

  def replicateRequestSource(request: Request[Source[ByteString, ?]], count: Int): EitherT[Future, PresentationError, List[Source[ByteString, ?]]] =
    replicateSource(request.body, count)

  private def materializeSource(source: Source[ByteString, ?]): EitherT[Future, PresentationError, Seq[ByteString]] =
    EitherT(
      source
        .runWith(Sink.seq)
        .map(Right(_): Either[PresentationError, Seq[ByteString]])
        .recover {
          error =>
            Left(PresentationError.internalServiceError(cause = Some(error)))
        }
    )

  def calculateSize(source: Source[ByteString, ?]): EitherT[Future, PresentationError, Long] = {
    val sizeFuture: Future[Either[PresentationError, Long]] = source
      .map(_.size.toLong)
      .runWith(Sink.fold(0L)(_ + _))
      .map(
        size => Right(size): Either[PresentationError, Long]
      )
      .recover {
        case _: Exception => Left(PresentationError.internalServiceError())
      }

    EitherT(sizeFuture)
  }
}
