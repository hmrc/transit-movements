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

package uk.gov.hmrc.transitmovements.generators

import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import uk.gov.hmrc.objectstore.client.Md5Hash
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MessageStatus
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.Movement
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.MovementType
import uk.gov.hmrc.transitmovements.models.requests.UpdateMessageMetadata
import uk.gov.hmrc.transitmovements.models.responses.MessageResponse

import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
      Gen.listOfN(16, Gen.hexChar).map(_.mkString).map(MovementId)
    }

  implicit lazy val arbitraryMessageId: Arbitrary[MessageId] =
    Arbitrary {
      Gen.listOfN(16, Gen.hexChar).map(_.mkString).map(MessageId)
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
      } yield Message(id, received, generated, messageType, triggerId, url, body, Some(status))
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

  implicit lazy val arbitraryMessageResponse: Arbitrary[MessageResponse] =
    Arbitrary {
      for {
        id             <- arbitrary[MessageId]
        offsetDateTime <- arbitrary[OffsetDateTime]
        messageType    <- arbitrary[MessageType]
        status         <- Gen.oneOf(MessageStatus.statusValues)
      } yield MessageResponse(id, offsetDateTime, messageType, None, Some(status))
    }

  implicit lazy val arbitraryUpdateMessageMetadata: Arbitrary[UpdateMessageMetadata] =
    Arbitrary {
      for {
        status <- Gen.oneOf(MessageStatus.statusValues)
      } yield UpdateMessageMetadata(None, status)
    }

  implicit lazy val arbitraryObjectSummaryWithMd5: Arbitrary[ObjectSummaryWithMd5] = Arbitrary {
    for {
      movementId <- arbitrary[MovementId]
      messageId  <- arbitrary[MessageId]
      lastModified      = Instant.now()
      formattedDateTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(lastModified)
      contentLen <- Gen.long
      hash       <- Gen.alphaNumStr.map(Md5Hash)
    } yield ObjectSummaryWithMd5(
      Path.Directory("xxxxx").file(s"${movementId.value}-${messageId.value}-$formattedDateTime.xml"),
      contentLen,
      hash,
      lastModified
    )
  }

  implicit lazy val arbitraryMessageStatus: Arbitrary[MessageStatus] =
    Arbitrary {
      Gen.oneOf(MessageStatus.statusValues)
    }
}
