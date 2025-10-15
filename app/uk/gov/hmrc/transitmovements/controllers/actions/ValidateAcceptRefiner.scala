package uk.gov.hmrc.transitmovements.controllers.actions

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import play.api.libs.json.Json
import play.api.mvc.Results.Status
import play.api.mvc.ActionBuilder
import play.api.mvc.ActionRefiner
import play.api.mvc.AnyContent
import play.api.mvc.BodyParser
import play.api.mvc.ControllerComponents
import play.api.mvc.Request
import play.api.mvc.Result
import play.api.mvc.WrappedRequest
import uk.gov.hmrc.transitmovements.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovements.models.APIVersionHeader

import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

final case class ValidatedVersionRequest[T](
  versionHeader: APIVersionHeader,
  request: ContentTypeRequest[T]
) extends WrappedRequest[T](request)

final class ValidateAcceptRefiner @Inject() (cc: ControllerComponents)(implicit val ec: ExecutionContext, mat: Materializer)
    extends ActionRefiner[ContentTypeRequest, ValidatedVersionRequest]
    with ActionBuilder[ValidatedVersionRequest, AnyContent] {

  private def validateAcceptHeader(request: ContentTypeRequest[?]): Either[PresentationError, APIVersionHeader] =
    for {
      acceptHeaderValue <-
        request.request.headers
          .get("APIVersion")
          .toRight(PresentationError.notAcceptableError("An Accept Header is missing."))

      version <-
        APIVersionHeader
          .fromString(acceptHeaderValue)
          .toRight(PresentationError.unsupportedMediaTypeError(s"The Accept header $acceptHeaderValue is not supported."))
    } yield version

  def refine[A](request: ContentTypeRequest[A]): Future[Either[Result, ValidatedVersionRequest[A]]] =
    validateAcceptHeader(request) match {
      case Left(error) =>
        clearSource(request)
        Future.successful(Left(Status(error.code.statusCode)(Json.toJson(error))))
      case Right(versionHeader) =>
        Future.successful(Right(ValidatedVersionRequest(versionHeader, request)))
    }

  private def clearSource(request: ContentTypeRequest[?]): Unit =
    request.request.body match {
      case source: Source[_, _] => val _ = source.runWith(Sink.ignore)
      case _                    => ()
    }

  override def parser: BodyParser[AnyContent] = cc.parsers.defaultBodyParser

  override protected def executionContext: ExecutionContext = ec
}
