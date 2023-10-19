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

package uk.gov.hmrc.transitmovements.models.formats

import com.google.inject.Inject
import play.api.libs.json.Format
import play.api.libs.json.JsError
import play.api.libs.json.JsString
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.Writes
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.crypto.SymmetricCryptoFactory
import uk.gov.hmrc.crypto.json.JsonEncryption
import uk.gov.hmrc.mongo.play.json.formats.MongoBinaryFormats
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.formats.MongoUuidFormats
import uk.gov.hmrc.transitmovements.config.AppConfig
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoMessageMetadata
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoMessageMetadataAndBody
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoMovementSummary
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoPaginatedMessages
import uk.gov.hmrc.transitmovements.models.mongo.read.MongoPaginatedMovements
import uk.gov.hmrc.transitmovements.models.mongo.write.MongoMessage
import uk.gov.hmrc.transitmovements.models.mongo.write.MongoMovement

import java.time.OffsetDateTime
import java.time.ZoneOffset
import scala.util.Try
import scala.util.control.NonFatal

class MongoFormats @Inject() (appConfig: AppConfig)
    extends CommonFormats
    with MongoBinaryFormats.Implicits
    with MongoJavatimeFormats.Implicits
    with MongoUuidFormats.Implicits {

  implicit lazy val crypto: Encrypter with Decrypter = SymmetricCryptoFactory.aesGcmCrypto(appConfig.encryptionKey)

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

  implicit val offsetDateTimeReads: Reads[OffsetDateTime] = Reads {
    value =>
      jatLocalDateTimeFormat
        .reads(value)
        .map(
          localDateTime => localDateTime.atOffset(ZoneOffset.UTC)
        )
  }

  implicit val offsetDateTimeWrites: Writes[OffsetDateTime] = Writes {
    value => jatLocalDateTimeFormat.writes(value.toLocalDateTime)
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
