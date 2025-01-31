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

package test.uk.gov.hmrc.transitmovements.it.generators

import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.transitmovements.models.ClientId
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.LocalReferenceNumber
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.MessageSender
import uk.gov.hmrc.transitmovements.models.MessageStatus
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.MovementType
import uk.gov.hmrc.transitmovements.models.mongo.write.MongoMessage
import uk.gov.hmrc.transitmovements.models.mongo.write.MongoMovement

import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

trait ModelGenerators extends BaseGenerators {

  implicit lazy val ArbitraryEoriNumber: Arbitrary[EORINumber] =
    Arbitrary {
      for {
        id <- IntWithMaxLength(9)
      } yield EORINumber(id.toString)
    }

  implicit lazy val ArbitraryMovementType: Arbitrary[MovementType] =
    Arbitrary(Gen.oneOf(MovementType.movementTypes))

  implicit lazy val ArbitraryMovementId: Arbitrary[MovementId] =
    Arbitrary {
      Gen
        .listOfN(16, Gen.hexChar)
        .map(
          id => MovementId(id.mkString)
        )
    }

  implicit lazy val arbitraryClientId: Arbitrary[ClientId] = Arbitrary {
    Gen.stringOfN(24, Gen.alphaNumChar).map(ClientId.apply)
  }

  implicit lazy val ArbitraryMessageId: Arbitrary[MessageId] =
    Arbitrary {
      for {
        id <- IntWithMaxLength(9)
      } yield MessageId(id.toString)
    }

  implicit lazy val ArbitraryMessageType: Arbitrary[MessageType] =
    Arbitrary(Gen.oneOf(MessageType.values))

  implicit lazy val ArbitraryURI: Arbitrary[URI] =
    Arbitrary(new URI("http://www.google.com"))

  implicit lazy val ArbitraryMovementReferenceNumber: Arbitrary[MovementReferenceNumber] =
    Arbitrary {
      for {
        year <- Gen
          .choose(0, 99)
          .map(
            y => f"$y%02d"
          )
        country <- Gen.pick(2, 'A' to 'Z')
        serial  <- Gen.pick(13, ('A' to 'Z') ++ ('0' to '9'))
      } yield MovementReferenceNumber(year ++ country.mkString ++ serial.mkString)
    }

  implicit lazy val ArbitraryLRN: Arbitrary[LocalReferenceNumber] =
    Arbitrary {
      Gen.alphaNumStr.map(LocalReferenceNumber(_))
    }

  // Restricts the date times to the range of positive long numbers to avoid overflows.
  implicit lazy val ArbitraryOffsetDateTime: Arbitrary[OffsetDateTime] =
    Arbitrary {
      for {
        millis <- Gen.chooseNum(0L, Long.MaxValue / 1000L)
      } yield OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC)
    }

  implicit lazy val ArbitraryMessage: Arbitrary[MongoMessage] =
    Arbitrary {
      for {
        id          <- arbitrary[MessageId]
        received    <- arbitrary[OffsetDateTime]
        generated   <- arbitrary[Option[OffsetDateTime]]
        messageType <- arbitrary[MessageType]
        triggerId   <- arbitrary[Option[MessageId]]
        url         <- arbitrary[Option[URI]]
        body        <- arbitrary[Option[String]].map(_.map(SensitiveString.apply))
        size        <- Gen.chooseNum(1L, 250000L)
        status      <- Gen.oneOf(MessageStatus.statusValues)
      } yield MongoMessage(id, received, generated, Some(messageType), triggerId, url, body, Some(size), Some(status))
    }

  implicit lazy val ArbitraryMovement: Arbitrary[MongoMovement] =
    Arbitrary {
      for {
        id                      <- arbitrary[MovementId]
        movementType            <- arbitrary[MovementType]
        eori                    <- arbitrary[EORINumber]
        movementReferenceNumber <- arbitrary[Option[MovementReferenceNumber]]
        movementLRN             <- arbitrary[Option[LocalReferenceNumber]]
        messageSender           <- arbitrary[Option[MessageSender]]
        created                 <- arbitrary[OffsetDateTime]
        updated                 <- arbitrary[OffsetDateTime]
        messages                <- arbitrary[Vector[MongoMessage]]
        clientId                <- arbitrary[Option[ClientId]]
        isTransitional          <- arbitrary[Option[Boolean]]
      } yield MongoMovement(
        id,
        movementType,
        eori,
        Some(eori),
        movementReferenceNumber,
        movementLRN,
        messageSender,
        created,
        updated,
        messages,
        clientId,
        isTransitional
      )
    }

  implicit lazy val ArbitraryMessageSender: Arbitrary[MessageSender] =
    Arbitrary {
      Gen.alphaNumStr.map(MessageSender(_))
    }
}
