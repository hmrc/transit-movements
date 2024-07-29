/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.transitmovements.routing

import cats.implicits.catsSyntaxEitherId
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import play.api.Logging
import play.api.libs.json.JsValue
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import play.api.mvc.Request
import play.api.mvc.Result
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.transitmovements.config.Constants
import uk.gov.hmrc.transitmovements.controllers.{MessageBodyController => TransitionalMessageBodyController}
import uk.gov.hmrc.transitmovements.controllers.{MovementsController => TransitionalMovementsController}
import uk.gov.hmrc.transitmovements.models.{MovementType => TransitionalMovementType}
import uk.gov.hmrc.transitmovements.stream.StreamingParsers
import uk.gov.hmrc.transitmovements.v2_1.controllers.MessageBodyController
import uk.gov.hmrc.transitmovements.v2_1.controllers.MovementsController
import uk.gov.hmrc.transitmovements.models.requests.common.EORINumber
import uk.gov.hmrc.transitmovements.models.requests.common.ItemCount
import uk.gov.hmrc.transitmovements.models.requests.common.LocalReferenceNumber
import uk.gov.hmrc.transitmovements.models.requests.common.MessageId
import uk.gov.hmrc.transitmovements.models.requests.common.MovementId
import uk.gov.hmrc.transitmovements.models.requests.common.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.requests.common.PageNumber
import uk.gov.hmrc.transitmovements.v2_1.models.MovementType

import java.time.OffsetDateTime
import javax.inject.Inject
import scala.concurrent.Future

class VersionedRoutingController @Inject() (
  cc: ControllerComponents,
  transitionalMessageBodyController: TransitionalMessageBodyController,
  messageBodyController: MessageBodyController,
  transitionalMovementsController: TransitionalMovementsController,
  movementsController: MovementsController
)(implicit val materializer: Materializer)
    extends BackendController(cc)
    with Logging
    with StreamingParsers {

  def getBody(eori: EORINumber, movementType: String, movementId: MovementId, messageId: MessageId): Action[AnyContent] =
    Action.async {
      implicit request =>
        if (useV2_1(request)) {
          validateMovementType(movementType).flatMap {
            mType =>
              messageBodyController.getBody(eori, mType, movementId, messageId)(request).asRight
          }.merge
        } else {
          validateTransitionalMovementType(movementType).flatMap {
            mType =>
              transitionalMessageBodyController.getBody(eori, mType, movementId, messageId)(request).asRight
          }.merge
        }
    }

  def createBody(eori: EORINumber, movementType: String, movementId: MovementId, messageId: MessageId): Action[Source[ByteString, _]] =
    Action.async(streamFromMemory) {
      implicit request =>
        if (useV2_1) {
          validateMovementType(movementType).flatMap {
            mType =>
              messageBodyController.createBody(eori, mType, movementId, messageId)(request).asRight
          }.merge
        } else {
          validateTransitionalMovementType(movementType).flatMap {
            mType =>
              transitionalMessageBodyController.createBody(eori, mType, movementId, messageId)(request).asRight
          }.merge
        }
    }

  def createMovement(eori: EORINumber, movementType: String): Action[Source[ByteString, _]] =
    Action.async(streamFromMemory) {
      implicit request =>
        if (useV2_1) {
          validateMovementType(movementType).flatMap {
            mType =>
              movementsController.createMovement(eori, mType)(request).asRight
          }.merge
        } else {
          validateTransitionalMovementType(movementType).flatMap {
            mType =>
              transitionalMovementsController.createMovement(eori, mType)(request).asRight
          }.merge
        }
    }

  def getMovementWithoutMessages(eori: EORINumber, movementType: String, movementId: MovementId): Action[AnyContent] =
    Action.async {
      implicit request =>
        if (useV2_1) {
          validateMovementType(movementType).flatMap {
            mType =>
              movementsController.getMovementWithoutMessages(eori, mType, movementId)(request).asRight
          }.merge
        } else {
          validateTransitionalMovementType(movementType).flatMap {
            mType =>
              transitionalMovementsController.getMovementWithoutMessages(eori, mType, movementId)(request).asRight
          }.merge
        }
    }

  def getMessage(eori: EORINumber, movementType: String, movementId: MovementId, messageId: MessageId): Action[AnyContent] =
    Action.async {
      implicit request =>
        if (useV2_1) {
          validateMovementType(movementType).flatMap {
            mType =>
              movementsController.getMessage(eori, mType, movementId, messageId)(request).asRight
          }.merge
        } else {
          validateTransitionalMovementType(movementType).flatMap {
            mType =>
              transitionalMovementsController.getMessage(eori, mType, movementId, messageId)(request).asRight
          }.merge
        }
    }

  def updateMessage(eori: EORINumber, movementType: String, movementId: MovementId, messageId: MessageId): Action[JsValue] =
    Action.async(parse.json) {
      implicit request =>
        if (useV2_1) {
          validateMovementType(movementType).flatMap {
            mType =>
              movementsController.updateMessage(eori, mType, movementId, messageId)(request).asRight
          }.merge
        } else {
          validateTransitionalMovementType(movementType).flatMap {
            mType =>
              transitionalMovementsController.updateMessage(eori, mType, movementId, messageId)(request).asRight
          }.merge
        }
    }

  def updateMovement(movementId: MovementId, triggerId: Option[MessageId]): Action[Source[ByteString, _]] =
    Action.async(streamFromMemory) {
      implicit request =>
        if (useV2_1)
          movementsController.updateMovement(movementId, triggerId)(request)
        else
          transitionalMovementsController.updateMovement(movementId, triggerId)(request)
    }

  def updateMessageStatus(movementId: MovementId, messageId: MessageId): Action[JsValue] =
    Action.async(parse.json) {
      implicit request =>
        if (useV2_1)
          movementsController.updateMessageStatus(movementId, messageId)(request)
        else
          transitionalMovementsController.updateMessageStatus(movementId, messageId)(request)
    }

  def getMessages(
    eori: EORINumber,
    movementType: String,
    movementId: MovementId,
    receivedSince: Option[OffsetDateTime],
    page: Option[PageNumber],
    count: Option[ItemCount],
    receivedUntil: Option[OffsetDateTime]
  ): Action[AnyContent] =
    Action.async {
      implicit request =>
        if (useV2_1) {
          validateMovementType(movementType).flatMap {
            mType =>
              movementsController
                .getMessages(eori, mType, movementId, receivedSince, page, count, receivedUntil)(request)
                .asRight
          }.merge
        } else {
          validateTransitionalMovementType(movementType).flatMap {
            mType =>
              transitionalMovementsController.getMessages(eori, mType, movementId, receivedSince, page, count, receivedUntil)(request).asRight
          }.merge
        }
    }

  def getMovementsForEori(
    eoriNumber: EORINumber,
    movementType: String,
    updatedSince: Option[OffsetDateTime] = None,
    movementEORI: Option[EORINumber] = None,
    movementReferenceNumber: Option[MovementReferenceNumber] = None,
    page: Option[PageNumber] = None,
    count: Option[ItemCount] = None,
    receivedUntil: Option[OffsetDateTime] = None,
    localReferenceNumber: Option[LocalReferenceNumber] = None
  ): Action[AnyContent] =
    Action.async {
      implicit request =>
        if (useV2_1) {
          validateMovementType(movementType).flatMap {
            mType =>
              movementsController
                .getMovementsForEori(
                  eoriNumber,
                  mType,
                  updatedSince,
                  movementEORI,
                  movementReferenceNumber,
                  page,
                  count,
                  receivedUntil,
                  localReferenceNumber
                )(request)
                .asRight
          }.merge
        } else {
          validateTransitionalMovementType(movementType).flatMap {
            mType =>
              transitionalMovementsController
                .getMovementsForEori(
                  eoriNumber,
                  mType,
                  updatedSince,
                  movementEORI,
                  movementReferenceNumber,
                  page,
                  count,
                  receivedUntil,
                  localReferenceNumber
                )(request)
                .asRight
          }.merge
        }
    }

  private def validateTransitionalMovementType(movementType: String): Either[Future[Result], TransitionalMovementType] =
    TransitionalMovementType.movementTypes.find(_.urlFragment == movementType) match {
      case Some(value) => value.asRight
      case None        => Future.successful(BadRequest(s"Invalid movement type: $movementType")).asLeft
    }

  private def validateMovementType(movementType: String): Either[Future[Result], MovementType] =
    MovementType.movementTypes.find(_.urlFragment == movementType) match {
      case Some(value) => value.asRight
      case None        => Future.successful(BadRequest(s"Invalid movement type: $movementType")).asLeft
    }

  private def useV2_1[T <: Request[_]](implicit request: Request[_]): Boolean =
    request.headers.get(Constants.APIVersionHeaderKey).map(_.trim.toLowerCase).contains("final")
}
