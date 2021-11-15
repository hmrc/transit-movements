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
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import config.Constants
import models.MessageType
import models.errors.TransitMovementError
import models.formats.HttpFormats
import play.api.libs.json.Json
import play.api.mvc.BaseController
import play.api.mvc.Request
import play.api.mvc.Result

import scala.concurrent.Future

trait RequireHeaders { self: BaseController with HttpFormats =>
  def requireMessageTypeHeader[A <: MessageType](values: Set[A])(
    result: A => Future[Result]
  )(implicit system: ActorSystem, request: Request[Source[ByteString, _]]): Future[Result] =
    request.headers
      .get(Constants.MessageTypeHeader)
      .flatMap(code => values.find(_.code == code))
      .map(result)
      .getOrElse {
        request.body
          // The request body must be consumed
          .runWith(Sink.ignore)
          .map { _ =>
            val error =
              TransitMovementError.badRequestError("Missing or incorrect X-Message-Type header")
            val errorJson =
              Json.toJson[TransitMovementError](error)
            BadRequest(errorJson)
          }(system.dispatcher)
      }

}
