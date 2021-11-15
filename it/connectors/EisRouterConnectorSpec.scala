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
import com.github.tomakehurst.wiremock.client.WireMock._
import models.MessageType
import org.scalatest.EitherValues
import org.scalatest.Inside
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.inject.bind
import play.api.inject.guice.GuiceableModule
import play.api.test.Helpers._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse._

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

class EisRouterConnectorSpec
  extends AsyncFlatSpec
  with Matchers
  with EitherValues
  with Inside
  with WireMockSpec {

  val fixedInstant = OffsetDateTime.of(2021, 11, 15, 16, 9, 47, 0, ZoneOffset.UTC)
  val clock        = Clock.fixed(fixedInstant.toInstant, ZoneOffset.UTC)

  implicit val hc = HeaderCarrier()

  override def portConfigKeys: Seq[String] = Seq(
    "microservice.services.eis-router.port"
  )

  override protected def bindings: Seq[GuiceableModule] = Seq(
    bind[Clock].toInstance(clock)
  )

  "EisRouterConnector" should "return unit when all is successful" in {
    val connector = injector.instanceOf[EisRouterConnector]

    wireMockServer.stubFor(
      post(urlEqualTo("/transits-movements-trader-at-departure-stub/movements/departures/gb"))
        .withHeader(HeaderNames.ACCEPT, equalTo(MimeTypes.XML))
        .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.XML))
        .withHeader(HeaderNames.DATE, equalTo("Mon, 15 Nov 2021 16:09:47 GMT"))
        .withHeader("Channel", equalTo("api"))
        .withHeader("CustomProcessHost", equalTo("Digital"))
        .withHeader("X-Message-Sender", equalTo("MDTP-00000000000000000000001-01"))
        .withHeader("X-Message-Type", equalTo("IE015"))
        .withRequestBody(equalTo("<foo></foo>"))
        .willReturn(aResponse().withStatus(ACCEPTED))
    )

    connector
      .sendMessage(MessageType.DeclarationData, Source.single(ByteString("<foo></foo>")))
      .map { response =>
        response shouldBe a[Right[_, _]]
        response.value.shouldBe(())
      }
  }

  it should "return an error response if the downstream component returns a client error" in {

    val connector = injector.instanceOf[EisRouterConnector]

    wireMockServer.stubFor(
      post(urlEqualTo("/transits-movements-trader-at-departure-stub/movements/departures/gb"))
        .willReturn(aResponse().withStatus(FORBIDDEN))
    )

    connector
      .sendMessage(MessageType.DeclarationData, Source.single(ByteString("<foo></foo>")))
      .map { response =>
        response shouldBe a[Left[_, _]]
        inside(response.left.value) { case Upstream4xxResponse(response) =>
          response.statusCode shouldBe FORBIDDEN
        }
      }
  }

  it should "return an error response if the downstream component returns a server error" in {

    val connector = injector.instanceOf[EisRouterConnector]

    wireMockServer.stubFor(
      post(urlEqualTo("/transits-movements-trader-at-departure-stub/movements/departures/gb"))
        .willReturn(aResponse().withStatus(BAD_GATEWAY))
    )

    connector
      .sendMessage(MessageType.DeclarationData, Source.single(ByteString("<foo></foo>")))
      .map { response =>
        response shouldBe a[Left[_, _]]
        inside(response.left.value) { case Upstream5xxResponse(response) =>
          response.statusCode shouldBe BAD_GATEWAY
        }
      }
  }
}
