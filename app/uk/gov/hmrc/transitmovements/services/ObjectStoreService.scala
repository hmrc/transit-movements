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

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import com.google.inject.ImplementedBy
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.objectstore.client.play.Implicits._
import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.transitmovements.models.ObjectStoreResourceLocation

import javax.inject._
import scala.concurrent._
import scala.util.control.NonFatal

@ImplementedBy(classOf[ObjectStoreServiceImpl])
trait ObjectStoreService {

  def getObjectStoreFile(
    objectStoreResourceLocation: ObjectStoreResourceLocation
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): EitherT[Future, ObjectStoreError, Source[ByteString, _]]

}

@Singleton
class ObjectStoreServiceImpl @Inject() (client: PlayObjectStoreClient) extends ObjectStoreService {

  override def getObjectStoreFile(
    objectStoreResourceLocation: ObjectStoreResourceLocation
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): EitherT[Future, ObjectStoreError, Source[ByteString, _]] =
    EitherT(
      client
        .getObject[Source[ByteString, NotUsed]](
          Path.File(objectStoreResourceLocation.value),
          "common-transit-convention-traders"
        )
        .flatMap {
          case Some(source) => Future.successful(Right(source.content))
          case _            => Future.successful(Left(ObjectStoreError.FileNotFound(objectStoreResourceLocation.value)))
        }
        .recover {
          case NonFatal(ex) => Left(ObjectStoreError.UnexpectedError(Some(ex)))
        }
    )
}
