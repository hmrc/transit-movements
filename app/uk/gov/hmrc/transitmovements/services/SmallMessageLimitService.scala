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

package uk.gov.hmrc.transitmovements.services

import com.google.inject.ImplementedBy
import uk.gov.hmrc.transitmovements.config.AppConfig

import javax.inject.Inject
import javax.inject.Singleton

@ImplementedBy(classOf[SmallMessageLimitServiceImpl])
trait SmallMessageLimitService {
  def isLarge(size: Long): Boolean
}

@Singleton
class SmallMessageLimitServiceImpl @Inject() (config: AppConfig) extends SmallMessageLimitService {
  private lazy val limit = config.smallMessageSizeLimit

  def isLarge(size: Long): Boolean = size > limit

}
