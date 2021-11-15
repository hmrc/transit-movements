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

package controllers

import akka.actor.ActorSystem
import models.MessageType
import models.formats.HttpFormats
import models.values.ArrivalId
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext

@Singleton
class ArrivalsController @Inject() (cc: ControllerComponents)(implicit system: ActorSystem)
  extends BackendController(cc)
  with HttpFormats
  with RequireHeaders
  with StreamingBodyParser {

  implicit val ec: ExecutionContext = system.dispatcher

  def sendArrivalNotification = Action.async(parse.stream) { implicit request =>
    ???
  }

  def receiveMessage(id: ArrivalId) = Action.async(parse.stream) { implicit request =>
    requireMessageTypeHeader(MessageType.arrivalValues) { messageType =>
      ???
    }
  }
}
