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

package uk.gov.hmrc.transitmovements.v2_1.models.formats

import com.google.inject.Inject
import play.api.libs.json._
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.crypto.json.JsonEncryption
import uk.gov.hmrc.mongo.play.json.formats.MongoBinaryFormats
import uk.gov.hmrc.mongo.play.json.formats.MongoUuidFormats
import uk.gov.hmrc.transitmovements.config.AppConfig
import uk.gov.hmrc.transitmovements.v2_1.models.mongo.read._
import uk.gov.hmrc.transitmovements.v2_1.models.mongo.write.MongoMessage
import uk.gov.hmrc.transitmovements.v2_1.models.mongo.write.MongoMovement

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import scala.util.Try
import scala.util.control.NonFatal

class MongoFormats @Inject() (appConfig: AppConfig)(implicit crypto: Encrypter with Decrypter)
    extends CommonFormats
    with MongoBinaryFormats.Implicits
    with MongoUuidFormats.Implicits {

  implicit lazy val writes: Writes[SensitiveString] =
    JsonEncryption.stringEncrypter.contramap(_.decryptedValue)

  lazy val baseReads: Reads[SensitiveString] =
    JsonEncryption.stringDecrypter.map(SensitiveString.apply)

  lazy val readsWithNoFallback: Reads[SensitiveString] = Reads {
    value =>
      Try {
        baseReads.reads(value)
      }.recover {
        case NonFatal(err) =>
          JsError(s"Failed to decrypt: ${err.getMessage}")
      }.get // if it blows up here, that's fine, because the error was fatal anyway
  }

  lazy val readsWithFallback: Reads[SensitiveString] = Reads {
    value =>
      Try {
        baseReads.reads(value)
      }.recover {
        case NonFatal(_) =>
          value
            .validate[JsString]
            .map(
              x => SensitiveString(x.value)
            )
      }.get // if it blows up here, that's fine, because the error was fatal anyway
  }

  implicit lazy val sensitiveStringFormat: Format[SensitiveString] =
    Format(if (appConfig.encryptionTolerantRead) readsWithFallback else readsWithNoFallback, writes)

  final val localDateTimeReads: Reads[LocalDateTime] =
    Reads
      .at[String](__ \ "$date" \ "$numberLong")
      .map(
        dateTime => Instant.ofEpochMilli(dateTime.toLong).atZone(ZoneOffset.UTC).toLocalDateTime
      )

  implicit val offsetDateTimeReads: Reads[OffsetDateTime] = Reads {
    value =>
      localDateTimeReads
        .reads(value)
        .map(
          localDateTime => localDateTime.atOffset(ZoneOffset.UTC)
        )
  }

  final val localDateTimeWrites: Writes[LocalDateTime] =
    Writes
      .at[String](__ \ "$date" \ "$numberLong")
      .contramap(_.toInstant(ZoneOffset.UTC).toEpochMilli.toString)

  implicit val offsetDateTimeWrites: Writes[OffsetDateTime] = Writes {
    value => localDateTimeWrites.writes(value.toLocalDateTime)
  }

  implicit val offsetDateTimeFormat: Format[OffsetDateTime] = Format.apply(offsetDateTimeReads, offsetDateTimeWrites)

  // these use the dates above, so need to be here for compile-time macro expansion
  implicit val messageFormat: Format[MongoMessage]                               = Json.format[MongoMessage]
  implicit val movementFormat: Format[MongoMovement]                             = Json.format[MongoMovement]
  implicit val movementSummaryFormat: Format[MongoMovementSummary]               = Json.format[MongoMovementSummary]
  implicit val messageMetadataFormat: Format[MongoMessageMetadata]               = Json.format[MongoMessageMetadata]
  implicit val messageMetadataAndBodyFormat: Format[MongoMessageMetadataAndBody] = Json.format[MongoMessageMetadataAndBody]
  implicit val paginationMovementSummaryFormat: Format[MongoPaginatedMovements]  = Json.format[MongoPaginatedMovements]
  implicit val paginationMessageSummaryFormat: Format[MongoPaginatedMessages]    = Json.format[MongoPaginatedMessages]

}
