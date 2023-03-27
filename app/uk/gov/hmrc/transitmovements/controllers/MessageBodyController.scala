package uk.gov.hmrc.transitmovements.controllers

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.transitmovements.controllers.errors.ConvertError
import uk.gov.hmrc.transitmovements.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementType
import uk.gov.hmrc.transitmovements.models.ObjectStoreURI
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse
import uk.gov.hmrc.transitmovements.repositories.MovementsRepository
import uk.gov.hmrc.transitmovements.services.ObjectStoreService
import uk.gov.hmrc.transitmovements.services.errors.ObjectStoreError

import java.net.URI
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class MessageBodyController(cc: ControllerComponents,
                            repo: MovementsRepository,
                            objectStoreService: ObjectStoreService)(implicit mat: Materializer, ec: ExecutionContext) extends BackendController(cc) with Logging with ConvertError with ObjectStoreURIHelpers {

  def getBody(eori: EORINumber, movementType: MovementType, movementId: MovementId, messageId: MessageId): Action[AnyContent] = Action.async {
    implicit request =>
      (for {
        maybeMessage <- repo.getSingleMessage(eori, movementId, messageId, movementType).asPresentation
        message      <- ensureMessage(maybeMessage, messageId, movementId)
        stream       <- body(message, messageId, movementId)
      } yield Ok.chunked(stream))
        .valueOrF(error => Future.successful(Status(error.code.statusCode)(Json.toJson(error))))
  }

  private def ensureMessage(message: Option[MessageResponse], messageId: MessageId, movementId: MovementId): EitherT[Future, PresentationError, MessageResponse] =
    message.map(EitherT.rightT[Future, PresentationError](_)).getOrElse(notFound(messageId, movementId))

  private def body(messageResponse: MessageResponse, messageId: MessageId, movementId: MovementId)(implicit hc: HeaderCarrier): EitherT[Future, PresentationError, Source[ByteString, _]] = {
    messageResponse match {
      case MessageResponse(_, _, _, Some(body), _, _) => EitherT.rightT(Source.single(ByteString(body)))
      case MessageResponse(_, _, _, None, _, Some(uri)) =>
        for {
          resourceLocation <- extractResourceLocation(ObjectStoreURI(uri.toString))
          source           <- objectStoreService.getObjectStoreFile(resourceLocation).asPresentation
        } yield source
      case _ => notFound(messageId, movementId)
    }
  }

  private def notFound[A](messageId: MessageId, movementId: MovementId): EitherT[Future, PresentationError, A] =
    EitherT.leftT(PresentationError.notFoundError(s"Body of message ID ${messageId.value} for movement ID ${movementId.value} was not found"))
}