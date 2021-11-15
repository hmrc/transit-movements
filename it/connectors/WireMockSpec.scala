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

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.common.ConsoleNotifier
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.Suite
import org.scalatestplus.play.guice.GuiceFakeApplicationFactory
import play.api.Application
import play.api.inject.Injector
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.inject.guice.GuiceableModule

trait WireMockSpec
  extends BeforeAndAfterEach
  with BeforeAndAfterAll
  with GuiceFakeApplicationFactory { suite: Suite =>

  protected val wireMockConfig =
    WireMockConfiguration.wireMockConfig().notifier(new ConsoleNotifier(false))

  protected val wireMockServer =
    new WireMockServer(wireMockConfig.dynamicPort())

  protected def portConfigKeys: Seq[String]

  override lazy val fakeApplication: Application =
    new GuiceApplicationBuilder()
      .configure(
        "metrics.jvm" -> false
      )
      .configure(
        portConfigKeys.map { key =>
          key -> wireMockServer.port.toString()
        }: _*
      )
      .overrides(bindings: _*)
      .build()

  protected def bindings: Seq[GuiceableModule] = Seq.empty

  protected lazy val injector: Injector = fakeApplication.injector

  override def beforeAll(): Unit = {
    wireMockServer.start()
    fakeApplication
    super.beforeAll()
  }

  override def beforeEach(): Unit = {
    wireMockServer.resetMappings()
    super.beforeEach()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    wireMockServer.stop()
  }
}
