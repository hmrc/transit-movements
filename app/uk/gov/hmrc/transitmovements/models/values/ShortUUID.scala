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

package uk.gov.hmrc.transitmovements.models.values

import org.apache.pekko.util
import org.apache.pekko.util.ByteString
import java.nio.ByteBuffer
import java.time.Clock
import java.util.Random
import scala.util.matching.Regex

object ShortUUID {
  val ShortUUIDRegex: Regex = """([0-9a-fA-F]{16})""".r

  def next(clock: Clock, random: Random): String = toHex(getNext(clock, random))

  private def getNext(clock: Clock, random: Random): util.ByteString = {
    val randomBuffer    = ByteBuffer.wrap(new Array[Byte](8))
    val timeComponent   = clock.instant().getEpochSecond << 32
    val bottom4Mask     = 0xffffffffL
    val randomComponent = random.nextLong() & bottom4Mask
    randomBuffer.putLong(timeComponent | randomComponent)
    ByteString(randomBuffer.array())
  }

  private def toHex(bytes: ByteString) =
    Range(0, bytes.length)
      .map(
        idx => f"${bytes(idx)}%02x"
      )
      .mkString("")
}
