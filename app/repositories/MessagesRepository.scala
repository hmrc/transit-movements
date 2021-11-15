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

import akka.pattern.retry
import akka.util.ByteString
import com.google.inject.ImplementedBy
import com.mongodb.reactivestreams.client.ClientSession
import models.Message
import models.MessageType
import models.formats.MongoFormats
import models.request.DeclarationDataRequest
import models.values.DepartureId
import models.values.MessageId
import org.mongodb.scala.model.IndexModel
import org.mongodb.scala.model.IndexOptions
import org.mongodb.scala.model.Indexes
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.objectstore.client.Path

import java.security.SecureRandom
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[MessagesRepositoryImpl])
trait MessagesRepository {
  def insertMessage(
    session: ClientSession,
    departureId: DepartureId,
    request: DeclarationDataRequest,
    messagePath: Path.File
  ): Future[MessageId]
}

@Singleton
class MessagesRepositoryImpl @Inject() (
  mongoComponent: MongoComponent,
  clock: Clock,
  random: SecureRandom
)(implicit ec: ExecutionContext)
  extends PlayMongoRepository[Message](
    mongoComponent = mongoComponent,
    collectionName = MessagesRepository.collectionName,
    domainFormat = MongoFormats.messageFormat,
    indexes = Seq(
      IndexModel(
        Indexes.ascending("departureId", "_id"),
        IndexOptions().unique(true).background(false)
      )
    )
  )
  with MessagesRepository
  with Logging {

  def insertMessage(
    session: ClientSession,
    departureId: DepartureId,
    request: DeclarationDataRequest,
    messagePath: Path.File
  ): Future[MessageId] =
    retry(
      attempts = 3,
      attempt = { () =>
        val messageId = MessageId.next(clock, random)

        val message = Message(
          messageId,
          departureId,
          request.preparationDateTime,
          MessageType.DeclarationData,
          messagePath
        )

        collection
          .insertOne(session, message)
          .headOption()
          .map { result =>
            MessageId(ByteString(result.get.getInsertedId.asBinary.getData))
          }
      }
    )

}

object MessagesRepository {
  val collectionName = "messages"
}
