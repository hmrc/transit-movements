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

package connectors

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.google.inject.ImplementedBy
import config.AppConfig
import models.RequestMessageType
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.libs.ws.WSClient
import uk.gov.hmrc.http.Authorization
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpErrorFunctions
import uk.gov.hmrc.http.UpstreamErrorResponse

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[EisRouterConnectorImpl])
trait EisRouterConnector {
  def sendMessage(
    messageType: RequestMessageType,
    messageData: Source[ByteString, _]
  )(implicit
    hc: HeaderCarrier
  ): Future[Either[UpstreamErrorResponse, Unit]]
}

class EisRouterConnectorImpl @Inject() (
  appConfig: AppConfig,
  wsClient: WSClient,
  clock: Clock
)(implicit ec: ExecutionContext)
  extends EisRouterConnector {

  private val hcConfig = HeaderCarrier.Config.fromConfig(appConfig.config.underlying)

  override def sendMessage(
    messageType: RequestMessageType,
    messageData: Source[ByteString, _]
  )(implicit hc: HeaderCarrier): Future[Either[UpstreamErrorResponse, Unit]] = {
    val routerUrl = appConfig.eisRouterUrl.toString

    // FIXME: This class is currently a hack designed to point directly to the departures stub service.
    // This is because the current EIS router component does not yet understand the NCTS Phase 5 messages.
    val headersForUrl = hc
      .copy(authorization = Some(Authorization("Bearer bearertokenhereGB")))
      .headersForUrl(hcConfig)(routerUrl) ++ Seq(
      "Channel"                -> "api",
      "CustomProcessHost"      -> "Digital",
      "X-Correlation-Id"       -> UUID.randomUUID().toString,
      "X-Message-Sender"       -> f"MDTP-${1}%023d-01",
      "X-Message-Type"         -> messageType.code,
      HeaderNames.ACCEPT       -> MimeTypes.XML,
      HeaderNames.CONTENT_TYPE -> MimeTypes.XML,
      HeaderNames.DATE -> DateTimeFormatter.RFC_1123_DATE_TIME.format(
        OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
      )
    )

    val response = wsClient
      .url(routerUrl)
      .withHttpHeaders(headersForUrl: _*)
      .post(messageData)

    response.map { res =>
      if (HttpErrorFunctions.is4xx(res.status) || HttpErrorFunctions.is5xx(res.status))
        Left(UpstreamErrorResponse(res.body, res.status))
      else
        Right(())
    }
  }

}
