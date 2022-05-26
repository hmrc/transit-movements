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

package uk.gov.hmrc.transitmovements.repositories

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

import cats.syntax.all._
import com.mongodb.reactivestreams.client.ClientSession
import org.mongodb.scala._
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.transaction.TransactionConfiguration

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

trait Transactions { self: Logging =>
  def mongoComponent: MongoComponent

  def withTransaction[A](
    block: ClientSession => Future[A]
  )(implicit tc: TransactionConfiguration, ec: ExecutionContext): Future[A] = {
    val client = mongoComponent.client

    for {
      session <- tc.clientSessionOptions.fold(client.startSession)(client.startSession).toFuture

      _ = tc.transactionOptions.fold(session.startTransaction)(session.startTransaction)

      runBlock = block(session)

      result <- runBlock

      closeTransaction = runBlock
        .transformWith {
          case Failure(e) =>
            logger.error("Aborting transaction due to exception", e)
            if (session.hasActiveTransaction())
              session.abortTransaction().toSingle.toFuture.void
            else
              Future.unit
          case Success(_) =>
            if (session.hasActiveTransaction())
              session.commitTransaction().toSingle.toFuture.void
            else
              Future.unit
        }
        .recoverWith {
          case NonFatal(_) =>
            Future.unit
        }
        .map {
          _ =>
            session.close()
        }

      _ <- closeTransaction

    } yield result
  }
}
