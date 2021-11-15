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

import akka.stream.scaladsl.Source
import akka.util.ByteString
import models.DepartureMessageType
import models.errors.TransitMovementError
import models.values.DepartureId
import models.values.EoriNumber
import models.values.MessageId
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Future

case class FakeDeparturesService(
  sendDeclarationResponse: () => Future[Either[TransitMovementError, DepartureId]] = () =>
    Future.failed(new NotImplementedError),
  receiveMessageResponse: () => Future[Either[TransitMovementError, MessageId]] = () =>
    Future.failed(new NotImplementedError)
) extends DeparturesService {

  override def sendDeclarationData(
    eoriNumber: EoriNumber,
    request: Source[ByteString, _]
  )(implicit hc: HeaderCarrier): Future[Either[TransitMovementError, DepartureId]] =
    sendDeclarationResponse()

  override def receiveMessage(
    departureId: DepartureId,
    messageType: DepartureMessageType,
    request: Source[ByteString, _]
  )(implicit hc: HeaderCarrier): Future[Either[TransitMovementError, MessageId]] =
    receiveMessageResponse()

}
