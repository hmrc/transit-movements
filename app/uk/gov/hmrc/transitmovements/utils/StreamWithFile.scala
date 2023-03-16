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

package uk.gov.hmrc.transitmovements.utils

import akka.stream.IOResult
import akka.stream.Materializer
import akka.stream.scaladsl.FileIO
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import cats.syntax.flatMap._
import play.api.Logging
import play.api.libs.Files.TemporaryFileCreator
import uk.gov.hmrc.transitmovements.controllers.errors.PresentationError

import java.nio.file.Files
import java.nio.file.Path
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

trait StreamWithFile {
  self: Logging =>

  def withReusableSourceAndSize[R](
    src: Source[ByteString, _]
  )(
    block: (Source[ByteString, _], Long) => EitherT[Future, PresentationError, R]
  )(implicit temporaryFileCreator: TemporaryFileCreator, mat: Materializer, ec: ExecutionContext): EitherT[Future, PresentationError, R] = {
    val file = temporaryFileCreator.create()
    (for {
      _      <- writeToFile(file, src)
      size   <- calculateSize(file)
      result <- block(FileIO.fromPath(file), size)
    } yield result)
      .flatTap {
        _ =>
          file.delete()
          EitherT.rightT(())
      }

  }

  private def calculateSize(file: Path): EitherT[Future, PresentationError, Long] =
    EitherT(
      Future.fromTry(
        Try(Files.size(file))
          .map(Right.apply)
          .recover {
            case NonFatal(e) => Left(PresentationError.internalServiceError(cause = Some(e)))
          }
      )
    )

  private def writeToFile(file: Path, src: Source[ByteString, _])(implicit
    mat: Materializer,
    ec: ExecutionContext
  ): EitherT[Future, PresentationError, IOResult] =
    EitherT(
      src
        .runWith(FileIO.toPath(file))
        .map(Right.apply)
        .recover {
          case NonFatal(thr) =>
            logger.error(s"Failed to create file stream: $thr", thr)
            Left(PresentationError.internalServiceError(cause = Some(thr)))
        }
    )

}
