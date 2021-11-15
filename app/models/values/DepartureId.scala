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

package models.values

import akka.util.ByteString
import play.api.Logging
import play.api.mvc.PathBindable

import java.time.Clock
import java.util.Random

case class DepartureId(id: ByteString) extends AnyVal with BytesToHex

object DepartureId extends HexToBytes with Logging {
  implicit val departureIdPathBindable: PathBindable[DepartureId] =
    new PathBindable.Parsing[DepartureId](
      { case ShortUUID.ShortUUIDRegex(hexString) =>
        DepartureId(fromHex(hexString))
      },
      _.hexString,
      (key, exc) => {
        logger.warn("Unable to parse departure ID value", exc)
        s"Cannot parse parameter $key as a departure ID value"
      }
    )

  def next(clock: Clock, random: Random): DepartureId =
    DepartureId(ShortUUID.next(clock, random))
}
