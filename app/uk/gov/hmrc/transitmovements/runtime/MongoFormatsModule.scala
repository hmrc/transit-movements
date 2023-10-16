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

package uk.gov.hmrc.transitmovements.runtime

import com.google.inject.AbstractModule
import com.google.inject.Provides
import com.google.inject.Singleton
import uk.gov.hmrc.crypto.SymmetricCryptoFactory
import uk.gov.hmrc.transitmovements.config.AppConfig
import uk.gov.hmrc.transitmovements.models.formats.MovementMongoFormats

class MongoFormatsModule extends AbstractModule {

  override def configure(): Unit = super.configure()

  @Provides
  @Singleton
  def provideMongoFormats(appConfig: AppConfig): MovementMongoFormats =
    new MovementMongoFormats(appConfig.encryptionFallback)(SymmetricCryptoFactory.aesGcmCrypto(appConfig.encryptionKey))

}
