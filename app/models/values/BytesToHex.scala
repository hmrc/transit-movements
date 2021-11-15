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

trait BytesToHex extends Any { self: Product with AnyVal =>
  def id: ByteString

  override def toString: String =
    s"$productPrefix($hexString)"

  def hexString = BytesToHex.toHex(id)
}

object BytesToHex {
  def toHex(bytes: ByteString) = {
    val sb = new StringBuilder

    var idx = 0

    while (idx < bytes.length) {
      sb.append(f"${bytes(idx)}%02x")
      idx += 1
    }

    sb.toString
  }
}
