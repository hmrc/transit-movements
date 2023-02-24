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

import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import com.google.inject.ImplementedBy
import play.api.http.Status.NOT_FOUND
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.transitmovements.connectors.ObjectStoreConnector
import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError

import javax.inject._
import scala.concurrent._

@ImplementedBy(classOf[ObjectStoreServiceImpl])
trait ObjectStoreService {

  def getObjectStoreFile(
    objectStoreURI: String
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): EitherT[Future, ObjectStoreError, Source[ByteString, _]]

}

@Singleton
class ObjectStoreServiceImpl @Inject() (client: ObjectStoreConnector) extends ObjectStoreService {

  override def getObjectStoreFile(
    objectStoreURI: String
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): EitherT[Future, ObjectStoreError, Source[ByteString, _]] =
    EitherT(
      client
        .getObjectStoreFile(objectStoreURI)
        .map {
          objectStoreFile => Right(objectStoreFile)
        }
        .recover {
          case UpstreamErrorResponse(_, NOT_FOUND, _, _) => Left(ObjectStoreError.FileNotFound(objectStoreURI))
          case thr                                       => Left(ObjectStoreError.UnexpectedError(Some(thr)))
        }
    )

}
