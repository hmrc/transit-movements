/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.transitmovements.controllers.actions

import play.api.mvc.ActionRefiner
import play.api.mvc.Result
import play.api.mvc.WrappedRequest
import uk.gov.hmrc.transitmovements.config.Constants
import uk.gov.hmrc.transitmovements.models.ClientId

import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

final case class ClientIdAndVersionRequest[T](
  request: ValidatedVersionRequest[T],
  clientId: Option[ClientId]
) extends WrappedRequest[T](request)

final class ClientIdRefiner @Inject() ()(implicit val ec: ExecutionContext) extends ActionRefiner[ValidatedVersionRequest, ClientIdAndVersionRequest] {

  def refine[A](request: ValidatedVersionRequest[A]): Future[Either[Result, ClientIdAndVersionRequest[A]]] = {
    val clientId = request.request.headers.get(Constants.XClientIdHeader).map(ClientId(_))
    Future.successful(Right(ClientIdAndVersionRequest(request, clientId)))
  }

  override protected def executionContext: ExecutionContext = ec
}
