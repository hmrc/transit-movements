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
import cats.syntax.all._
import models.MessageType
import models.errors.InternalServiceError
import models.errors.NotFoundError
import models.errors.TransitMovementError
import models.errors.UpstreamServiceError
import models.errors.XmlValidationError
import models.formats.HttpFormats
import models.values.DepartureId
import models.values.EoriNumber
import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import services.DeparturesService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

@Singleton
class DeparturesController @Inject() (
  service: DeparturesService,
  cc: ControllerComponents
)(implicit system: ActorSystem)
  extends BackendController(cc)
  with HttpFormats
  with Logging
  with ErrorLogging
  with RequireHeaders
  with StreamingBodyParser {

  implicit val ec: ExecutionContext = system.dispatcher

  def sendDeclarationData = Action.async(parse.stream) { implicit request =>
    service
      // TODO: Where will this EORI number come from if we don't do auth here?
      .sendDeclarationData(EoriNumber("GB123456789000"), request.body)
      .flatTap(logServiceError("submitting declaration data", _))
      .map {
        case Right(departureId) =>
          Accepted(Json.toJson(departureId.hexString))
        case Left(error @ XmlValidationError(_, _)) =>
          BadRequest(Json.toJson[TransitMovementError](error))
        case Left(error @ UpstreamServiceError(_, _)) =>
          InternalServerError(Json.toJson[TransitMovementError](error))
        case Left(error @ InternalServiceError(_, _)) =>
          InternalServerError(Json.toJson[TransitMovementError](error))
        case Left(_) =>
          InternalServerError(Json.toJson(TransitMovementError.internalServiceError()))
      }
      .recover { case NonFatal(e) =>
        logger.error("Unhandled exception thrown", e)
        val error     = InternalServiceError.causedBy(e)
        val errorJson = Json.toJson[TransitMovementError](error)
        InternalServerError(errorJson)
      }
  }

  def receiveMessage(id: DepartureId) = Action.async(parse.stream) { implicit request =>
    requireMessageTypeHeader(MessageType.departureValues) { messageType =>
      service
        // TODO: How will we check the user has access to the departure if we don't do auth here?
        .receiveMessage(id, messageType, request.body)
        .flatTap(logServiceError(s"sending message for departure with ID ${id.hexString}", _))
        .map {
          case Right(messageId) =>
            Accepted(Json.toJson(messageId.hexString))
          case Left(error @ NotFoundError(_)) =>
            NotFound(Json.toJson[TransitMovementError](error))
          case Left(error @ XmlValidationError(_, _)) =>
            BadRequest(Json.toJson[TransitMovementError](error))
          case Left(error @ UpstreamServiceError(_, _)) =>
            InternalServerError(Json.toJson[TransitMovementError](error))
          case Left(error @ InternalServiceError(_, _)) =>
            InternalServerError(Json.toJson[TransitMovementError](error))
          case Left(_) =>
            InternalServerError(Json.toJson(TransitMovementError.internalServiceError()))
        }
        .recover { case NonFatal(e) =>
          logger.error("Unhandled exception thrown", e)
          val error     = InternalServiceError.causedBy(e)
          val errorJson = Json.toJson[TransitMovementError](error)
          InternalServerError(errorJson)
        }
    }
  }
}
