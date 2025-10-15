package uk.gov.hmrc.transitmovements.controllers.actions

import cats.implicits.catsSyntaxEitherId
import com.google.inject.Inject
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import play.api.http.HeaderNames
import play.api.libs.json.Json
import play.api.mvc.{ActionBuilder, ActionRefiner, AnyContent, BodyParser, ControllerComponents, Request, Result, WrappedRequest}
import play.api.mvc.Results.UnsupportedMediaType
import uk.gov.hmrc.transitmovements.controllers.errors.PresentationError

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

final case class ContentTypeRequest[T](
  contentType: String,
  request: Request[T]
) extends WrappedRequest[T](request)

final class ContentTypeRefiner @Inject() (cc: ControllerComponents)(implicit val ec: ExecutionContext, mat: Materializer)
    extends ActionRefiner[Request, ContentTypeRequest]
    with ActionBuilder[ContentTypeRequest, AnyContent] {

  override def refine[A](request: Request[A]): Future[Either[Result, ContentTypeRequest[A]]] =
    Future.successful(request.headers.get("Content-Type") match {
      case Some(contentType) => ContentTypeRequest(contentType, request).asRight
      case None              =>
        //TODO: Maybe we should actually validate the content-type? 
        UnsupportedMediaType(Json.toJson(PresentationError.unsupportedMediaTypeError("A content-type header is required!"))).asLeft
    })

  private def clearSource(request: Request[?]): Unit =
    request.body match {
      case source: Source[_, _] => val _ = source.runWith(Sink.ignore)
      case _                    => ()
    }

  override def parser: BodyParser[AnyContent] = cc.parsers.defaultBodyParser

  override protected def executionContext: ExecutionContext = ec
}
