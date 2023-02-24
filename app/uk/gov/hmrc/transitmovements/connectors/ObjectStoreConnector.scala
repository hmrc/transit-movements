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

package uk.gov.hmrc.transitmovements.connectors

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.google.inject._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import play.api.http.Status._
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.play.Implicits._
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[ObjectStoreConnectorImpl])
trait ObjectStoreConnector {

  def getObjectStoreFile(objectStoreURL: String)(implicit
    ec: ExecutionContext,
    hc: HeaderCarrier
  ): Future[Source[ByteString, _]]

}

class ObjectStoreConnectorImpl @Inject() (client: PlayObjectStoreClient) extends ObjectStoreConnector {

  override def getObjectStoreFile(objectStoreURL: String)(implicit
    ec: ExecutionContext,
    hc: HeaderCarrier
  ): Future[Source[ByteString, _]] =
    client
      .getObject[Source[ByteString, NotUsed]](Path.File(objectStoreURL), "common-transit-conversion-traders")
      .flatMap {
        case Some(source) => Future.successful(source.content)
        case _            => Future.failed((new RuntimeException("File not found")))
      }

}
