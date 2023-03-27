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

package uk.gov.hmrc.transitmovements.it.generators

import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalacheck.Arbitrary.arbitrary
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageStatus
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.Movement
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.MovementType

import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

trait ModelGenerators extends BaseGenerators {

  implicit lazy val arbitraryEoriNumber: Arbitrary[EORINumber] =
    Arbitrary {
      for {
        id <- intWithMaxLength(9)
      } yield EORINumber(id.toString)
    }

  implicit lazy val arbitraryMovementType: Arbitrary[MovementType] =
    Arbitrary(Gen.oneOf(MovementType.movementTypes))

  implicit lazy val arbitraryMovementId: Arbitrary[MovementId] =
    Arbitrary {
      for {
        id <- intWithMaxLength(9)
      } yield MovementId(id.toString)
    }

  implicit lazy val arbitraryMessageId: Arbitrary[MessageId] =
    Arbitrary {
      for {
        id <- intWithMaxLength(9)
      } yield MessageId(id.toString)
    }

  implicit lazy val arbitraryMessageType: Arbitrary[MessageType] =
    Arbitrary(Gen.oneOf(MessageType.values))

  implicit lazy val arbitraryURI: Arbitrary[URI] =
    Arbitrary(new URI("http://www.google.com"))

  implicit lazy val arbitraryMovementReferenceNumber: Arbitrary[MovementReferenceNumber] =
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

  // Restricts the date times to the range of positive long numbers to avoid overflows.
  implicit lazy val arbitraryOffsetDateTime: Arbitrary[OffsetDateTime] =
    Arbitrary {
      for {
        millis <- Gen.chooseNum(0, Long.MaxValue / 1000L)
      } yield OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC)
    }

  implicit lazy val arbitraryMessage: Arbitrary[Message] =
    Arbitrary {
      for {
        id          <- arbitrary[MessageId]
        received    <- arbitrary[OffsetDateTime]
        generated   <- arbitrary[Option[OffsetDateTime]]
        messageType <- arbitrary[MessageType]
        triggerId   <- arbitrary[Option[MessageId]]
        url         <- arbitrary[Option[URI]]
        body        <- arbitrary[Option[String]]
        status      <- Gen.oneOf(MessageStatus.statusValues)
      } yield Message(id, received, generated, Some(messageType), triggerId, url, body, Some(status))
    }

  implicit lazy val arbitraryMovement: Arbitrary[Movement] =
    Arbitrary {
      for {
        id                      <- arbitrary[MovementId]
        movementType            <- arbitrary[MovementType]
        eori                    <- arbitrary[EORINumber]
        movementReferenceNumber <- arbitrary[Option[MovementReferenceNumber]]
        created                 <- arbitrary[OffsetDateTime]
        updated                 <- arbitrary[OffsetDateTime]
        messages                <- arbitrary[Vector[Message]]
      } yield Movement(id, movementType, eori, Some(eori), movementReferenceNumber, created, updated, messages)
    }
}
