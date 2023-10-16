/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.transitmovements.config

import javax.inject.Inject
import javax.inject.Singleton
import play.api.Configuration

@Singleton
class AppConfig @Inject() (
  config: Configuration
) {

  lazy val mongoRetryAttempts: Int = config.get[Int]("mongodb.retryAttempts")
  lazy val documentTtl: Long       = config.get[Long]("mongodb.timeToLiveInSeconds")

  lazy val smallMessageSizeLimit: Long  = config.get[Long]("smallMessageSizeLimit")
  lazy val internalAuthEnabled: Boolean = config.get[Boolean]("microservice.services.internal-auth.enabled")

  lazy val encryptionKey: String       = config.get[String]("encryption.key")
  lazy val encryptionFallback: Boolean = config.get[Boolean]("encryption.fallback")

}
