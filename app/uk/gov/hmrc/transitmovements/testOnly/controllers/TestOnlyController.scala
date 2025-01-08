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

package uk.gov.hmrc.transitmovements.testOnly.controllers

import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.transitmovements.repositories.MovementsRepositoryImpl

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class TestOnlyController @Inject() (
  cc: ControllerComponents,
  Repository: MovementsRepositoryImpl,
  repository: MovementsRepositoryImpl
)(implicit ec: ExecutionContext)
    extends BackendController(cc) {

  def dropCollection(): Action[AnyContent] = Action.async {
    _ =>
      val Query = Repository.collection
        .drop()
        .toFuture()
        .map(
          _ => Ok
        )

      val query = repository.collection
        .drop()
        .toFuture()
        .map(
          _ => Ok
        )

      for {
        _      <- Query
        result <- query
      } yield result
  }
}
