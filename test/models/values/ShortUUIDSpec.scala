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

import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.mvc.PathBindable

import java.security.SecureRandom
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Random

class ShortUUIDSpec
  extends AnyFlatSpec
  with Matchers
  with EitherValues
  with ScalaCheckPropertyChecks
  with HexToBytes {

  "ShortUUID.next" should "generate a short sequential UUID based upon the system time and random bytes" in {
    def newRandom    = new Random(0)
    val date         = LocalDateTime.of(2021, 9, 1, 9, 32, 31).toInstant(ZoneOffset.UTC)
    val clock        = Clock.fixed(date, ZoneOffset.UTC)
    val bytes        = ShortUUID.next(clock, newRandom)
    val shortUuidHex = BytesToHex.toHex(bytes)
    shortUuidHex shouldBe "612f48afd4d95138"
    shortUuidHex.take(8) shouldBe date.getEpochSecond.toHexString
    shortUuidHex.drop(8) shouldBe newRandom.nextLong.toHexString.drop(8)
  }

  val systemClock  = Clock.systemUTC
  val secureRandom = new SecureRandom

  def randomArrivalId   = Gen.delay(Gen.const(ArrivalId.next(systemClock, secureRandom)))
  def randomDepartureId = Gen.delay(Gen.const(DepartureId.next(systemClock, secureRandom)))
  def randomMessageId   = Gen.delay(Gen.const(MessageId.next(systemClock, secureRandom)))

  "ArrivalId" should "round trip to and from hex" in forAll(randomArrivalId) { arrivalId =>
    val arrivalHex       = arrivalId.hexString
    val arrivalIdFromHex = ArrivalId(ArrivalId.fromHex(arrivalHex))
    arrivalIdFromHex shouldBe arrivalId
  }

  it should "round trip as a URL path parameter" in forAll(randomArrivalId) { arrivalId =>
    val binder  = implicitly[PathBindable[ArrivalId]]
    val unbound = binder.unbind("id", arrivalId)
    val bound   = binder.bind("id", unbound)
    bound.value shouldBe arrivalId
  }

  it should "return an error when binding a URL path parameter containing invalid characters" in {
    val binder = implicitly[PathBindable[ArrivalId]]
    binder.bind("id", "612f48@fd4d95138") shouldBe Left(
      "Cannot parse parameter id as an arrival ID value"
    )
  }

  it should "show hex as toString value" in {
    val arrivalId = ArrivalId(fromHex("612f48afd4d95138"))
    arrivalId.toString shouldBe "ArrivalId(612f48afd4d95138)"
  }

  "DepartureId" should "round trip to and from hex" in forAll(randomDepartureId) { departureId =>
    val departureHex       = departureId.hexString
    val departureIdFromHex = DepartureId(DepartureId.fromHex(departureHex))
    departureIdFromHex shouldBe departureId
  }

  it should "round trip as a URL path parameter" in forAll(randomDepartureId) { departureId =>
    val binder  = implicitly[PathBindable[DepartureId]]
    val unbound = binder.unbind("id", departureId)
    val bound   = binder.bind("id", unbound)
    bound.value shouldBe departureId
  }

  it should "return an error when binding a URL path parameter containing invalid characters" in {
    val binder = implicitly[PathBindable[DepartureId]]
    binder.bind("id", "612f48@fd4d95138") shouldBe Left(
      "Cannot parse parameter id as a departure ID value"
    )
  }

  it should "show hex as toString value" in {
    val departureId = DepartureId(fromHex("612f48afd4d95138"))
    departureId.toString shouldBe "DepartureId(612f48afd4d95138)"
  }

  "MessageId" should "round trip to and from hex" in forAll(randomMessageId) { messageId =>
    val messageHex       = messageId.hexString
    val messageIdFromHex = MessageId(MessageId.fromHex(messageHex))
    messageIdFromHex shouldBe messageId
  }

  it should "round trip as a URL path parameter" in forAll(randomMessageId) { messageId =>
    val binder  = implicitly[PathBindable[MessageId]]
    val unbound = binder.unbind("id", messageId)
    val bound   = binder.bind("id", unbound)
    bound.value shouldBe messageId
  }

  it should "return an error when binding a URL path parameter containing invalid characters" in {
    val binder = implicitly[PathBindable[MessageId]]
    binder.bind("id", "612f48@fd4d95138") shouldBe Left(
      "Cannot parse parameter id as a message ID value"
    )
  }

  it should "show hex as toString value" in {
    val messageId = MessageId(fromHex("612f48afd4d95138"))
    messageId.toString shouldBe "MessageId(612f48afd4d95138)"
  }

}
