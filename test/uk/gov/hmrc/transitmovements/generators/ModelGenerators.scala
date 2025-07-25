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
import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.objectstore.client.Md5Hash
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.transitmovements.models.ClientId
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.ItemCount
import uk.gov.hmrc.transitmovements.models.LocalReferenceNumber
import uk.gov.hmrc.transitmovements.models.MessageId
import uk.gov.hmrc.transitmovements.models.MovementId
import uk.gov.hmrc.transitmovements.models.MovementReferenceNumber
import uk.gov.hmrc.transitmovements.models.PageNumber
import uk.gov.hmrc.transitmovements.models.Message
import uk.gov.hmrc.transitmovements.models.MessageSender
import uk.gov.hmrc.transitmovements.models.MessageStatus
import uk.gov.hmrc.transitmovements.models.MessageType
import uk.gov.hmrc.transitmovements.models.Movement
import uk.gov.hmrc.transitmovements.models.MovementType
import uk.gov.hmrc.transitmovements.models.ObjectStoreURI
import uk.gov.hmrc.transitmovements.models.UpdateMessageData
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoMessageMetadata
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoMessageMetadataAndBody
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoMovementEori
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoMovementSummary
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
      Gen.listOfN(16, Gen.hexChar).map(_.mkString).map(MovementId.apply)
    }

  implicit lazy val arbitraryMessageId: Arbitrary[MessageId] =
    Arbitrary {
      Gen.listOfN(16, Gen.hexChar).map(_.mkString).map(MessageId.apply)
    }

  implicit lazy val arbitraryMessageType: Arbitrary[MessageType] =
    Arbitrary(Gen.oneOf(MessageType.values))

  implicit lazy val arbClientId: Arbitrary[ClientId] = Arbitrary {
    Gen.stringOfN(24, Gen.alphaNumChar).map(ClientId.apply)
  }

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

  implicit lazy val arbitraryLRN: Arbitrary[LocalReferenceNumber] =
    Arbitrary {
      Gen.alphaNumStr.map(LocalReferenceNumber(_))
    }

  // Restricts the date times to the range of positive long numbers to avoid overflows.
  implicit lazy val arbitraryOffsetDateTime: Arbitrary[OffsetDateTime] =
    Arbitrary {
      for {
        millis <- Gen.chooseNum(0L, Long.MaxValue / 1000L)
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
        size        <- Gen.chooseNum(1L, 250000L)
        body        <- arbitrary[Option[String]]
        status      <- Gen.oneOf(MessageStatus.statusValues)
      } yield Message(id, received, generated, Some(messageType), triggerId, url, body, Some(size), Some(status))
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
        lrn                     <- arbitrary[Option[LocalReferenceNumber]]
        messageSender           <- arbitrary[Option[MessageSender]]
        clientId                <- arbitrary[Option[ClientId]]
        isTransitional          <- arbitrary[Boolean]
      } yield Movement(id, movementType, eori, Some(eori), movementReferenceNumber, lrn, messageSender, created, updated, messages, clientId, isTransitional)
    }

  implicit lazy val arbitraryMessageResponse: Arbitrary[MessageResponse] =
    Arbitrary {
      for {
        id             <- arbitrary[MessageId]
        offsetDateTime <- arbitrary[OffsetDateTime]
        messageType    <- Gen.option(arbitrary[MessageType])
        status         <- Gen.oneOf(MessageStatus.statusValues)
      } yield MessageResponse(id, offsetDateTime, messageType, None, Some(status), None)
    }

  implicit lazy val arbitraryUpdateMessageMetadata: Arbitrary[UpdateMessageMetadata] =
    Arbitrary {
      for {
        status <- Gen.oneOf(MessageStatus.statusValues)
      } yield UpdateMessageMetadata(None, status, None)
    }

  implicit lazy val arbitraryObjectSummaryWithMd5: Arbitrary[ObjectSummaryWithMd5] = Arbitrary {
    for {
      movementId <- arbitrary[MovementId]
      messageId  <- arbitrary[MessageId]
      lastModified      = Instant.now()
      formattedDateTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(lastModified)
      contentLen <- Gen.long
      hash       <- Gen.alphaNumStr.map(Md5Hash.apply)
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

  lazy val dateTimeFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

  private val objectStoreOwner = "transit-movements"

  def testObjectStoreURI(movementId: MovementId, messageId: MessageId, dateTime: OffsetDateTime): ObjectStoreURI =
    ObjectStoreURI(s"$objectStoreOwner/movements/${movementId.value}/${movementId.value}-${messageId.value}-${dateTimeFormat.format(dateTime)}.xml")

  implicit lazy val arbitraryObjectStoreURI: Arbitrary[ObjectStoreURI] =
    Arbitrary {
      for {
        movementId <- arbitrary[MovementId]
        messageId  <- arbitrary[MessageId]
        dateTime   <- arbitrary[OffsetDateTime]
      } yield testObjectStoreURI(movementId, messageId, dateTime)
    }

  implicit lazy val arbitraryPageNumber: Arbitrary[PageNumber] = Arbitrary {
    Gen.long.map(
      l => PageNumber(Math.abs(l % Int.MaxValue - 1).toInt) // require a positive integer
    )
  }

  implicit lazy val arbitraryItemCount: Arbitrary[ItemCount] = Arbitrary {
    Gen.long.map(
      l => ItemCount(Math.abs(l % (Int.MaxValue - 1)).toInt) // // require a positive integer
    )
  }

  implicit lazy val arbitraryMessageSender: Arbitrary[MessageSender] =
    Arbitrary {
      Gen.alphaNumStr.map(MessageSender(_))
    }

  implicit lazy val arbitraryUpdateMessageData: Arbitrary[UpdateMessageData] =
    Arbitrary {
      for {
        objectStoreUri <- Gen.option(arbitrary[ObjectStoreURI])
        body           <- Gen.option(Gen.stringOfN(20, Gen.alphaNumChar))
        size           <- Gen.option(Gen.choose(0L, Long.MaxValue))
        status         <- arbitrary[MessageStatus]
        messageType    <- Gen.option(arbitrary[MessageType])
        generationDate <- Gen.option(arbitrary[OffsetDateTime])
      } yield UpdateMessageData(
        objectStoreUri,
        body,
        size,
        status,
        messageType,
        generationDate
      )
    }

  implicit lazy val arbitraryMongoMessageMetadata: Arbitrary[MongoMessageMetadata] =
    Arbitrary {
      for {
        id             <- arbitrary[MessageId]
        offsetDateTime <- arbitrary[OffsetDateTime]
        messageType    <- Gen.option(arbitrary[MessageType])
        status         <- Gen.oneOf(MessageStatus.statusValues)
      } yield MongoMessageMetadata(id, offsetDateTime, messageType, Some(status))
    }

  implicit lazy val arbitraryMongoMessageMetadataAndBody: Arbitrary[MongoMessageMetadataAndBody] =
    Arbitrary {
      for {
        id             <- arbitrary[MessageId]
        offsetDateTime <- arbitrary[OffsetDateTime]
        messageType    <- Gen.option(arbitrary[MessageType])
        status         <- Gen.oneOf(MessageStatus.statusValues)
        osUrl          <- Gen.option(arbitrary[URI])
        body           <- Gen.option(Gen.alphaNumStr.map(SensitiveString.apply))
      } yield MongoMessageMetadataAndBody(id, offsetDateTime, messageType, osUrl, body, None, Some(status))
    }

  implicit lazy val arbitraryMongoMovementSummary: Arbitrary[MongoMovementSummary] =
    Arbitrary {
      for {
        id                      <- arbitrary[MovementId]
        eori                    <- arbitrary[EORINumber]
        movementEori            <- Gen.option(arbitrary[EORINumber])
        movementReferenceNumber <- arbitrary[Option[MovementReferenceNumber]]
        lrn                     <- arbitrary[Option[LocalReferenceNumber]]
        created                 <- arbitrary[OffsetDateTime]
        updated                 <- arbitrary[OffsetDateTime]
      } yield MongoMovementSummary(id, eori, movementEori, movementReferenceNumber, lrn, created, updated)
    }

  implicit lazy val arbitraryMongoMovementEori: Arbitrary[MongoMovementEori] =
    Arbitrary {
      for {
        id             <- arbitrary[MovementId]
        eori           <- arbitrary[EORINumber]
        clientId       <- Gen.option(arbitrary[ClientId])
        isTransitional <- arbitrary[Option[Boolean]]
      } yield MongoMovementEori(id, eori, clientId, isTransitional)
    }
}
