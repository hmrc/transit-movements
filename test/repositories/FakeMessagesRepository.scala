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

package repositories

import com.mongodb.reactivestreams.client.ClientSession
import models.request.DeclarationDataRequest
import models.values.DepartureId
import models.values.MessageId
import uk.gov.hmrc.objectstore.client.Path

import scala.concurrent.Future

case class FakeMessagesRepository(
  insertMessageResponse: () => Future[MessageId] = () => Future.failed(new NotImplementedError)
) extends MessagesRepository {

  override def insertMessage(
    session: ClientSession,
    departureId: DepartureId,
    request: DeclarationDataRequest,
    messagePath: Path.File
  ): Future[MessageId] =
    insertMessageResponse()

}
