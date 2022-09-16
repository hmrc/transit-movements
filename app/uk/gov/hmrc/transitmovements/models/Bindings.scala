/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.transitmovements.models

import play.api.mvc.PathBindable
import play.api.mvc.QueryStringBindable
import uk.gov.hmrc.transitmovements.models.formats.CommonFormats

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

object Bindings {

  implicit val offsetDateTimeQueryStringBindable: QueryStringBindable[OffsetDateTime] = {
    val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    new QueryStringBindable.Parsing[OffsetDateTime](
      OffsetDateTime.parse(_),
      dt => formatter.format(dt),
      (param, _) => s"Cannot parse parameter $param as a valid ISO 8601 timestamp, e.g. 2015-09-08T01:55:28+00:00"
    )
  }

  implicit def triggerIdQueryStringBindable: QueryStringBindable[MessageId] =
    new QueryStringBindable.Parsing[MessageId](
      triggerId => MessageId(triggerId),
      triggerId => triggerId.value,
      (param, _) => s"Cannot parse parameter $param as a valid string"
    )

}
