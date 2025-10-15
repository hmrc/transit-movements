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
import play.api.mvc.Result
import play.api.mvc.WrappedRequest
import uk.gov.hmrc.transitmovements.config.Constants
import uk.gov.hmrc.transitmovements.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovements.models.APIVersionHeader
import uk.gov.hmrc.transitmovements.models.ClientId

import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

final case class ClientIdAndVersionRequest[T](
  request: ValidatedVersionRequest[T],
  clientId: Option[ClientId]
) extends WrappedRequest[T](request)

final class ClientIdRefiner @Inject() (cc: ControllerComponents)(implicit val ec: ExecutionContext, mat: Materializer)
    extends ActionRefiner[ValidatedVersionRequest, ClientIdAndVersionRequest]
    with ActionBuilder[ClientIdAndVersionRequest, AnyContent] {

  def refine[A](request: ValidatedVersionRequest[A]): Future[Either[Result, ClientIdAndVersionRequest[A]]] = {
    // TODO: rename request to something sensible
    val clientId = request.request.headers.get(Constants.XClientIdHeader).map(ClientId)
    Future.successful(Right(ClientIdAndVersionRequest(request, clientId)))
  }

  override def parser: BodyParser[AnyContent] = cc.parsers.defaultBodyParser

  override protected def executionContext: ExecutionContext = ec
}
