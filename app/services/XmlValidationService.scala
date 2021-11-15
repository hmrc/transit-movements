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

package services

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.StreamConverters
import akka.util.ByteString
import cats.data.NonEmptyList
import cats.syntax.all._
import com.google.inject.ImplementedBy
import models.MessageType
import models.errors.SchemaValidationError
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import play.api.Logging

import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import javax.xml.validation.SchemaFactory
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._

@ImplementedBy(classOf[XmlValidationServiceImpl])
trait XmlValidationService {
  def validate(
    messageType: MessageType,
    xml: Source[ByteString, _]
  ): Future[Either[NonEmptyList[SchemaValidationError], Unit]]
}

@Singleton
class XmlValidationServiceImpl @Inject() ()(implicit mat: Materializer)
  extends XmlValidationService
  with Logging {

  implicit val ec: ExecutionContext = mat.executionContext

  val parsersByType: Map[MessageType, Future[SAXParserFactory]] =
    MessageType.values.map { typ =>
      typ -> Future(buildParser(typ))
    }.toMap

  def buildParser(messageType: MessageType): SAXParserFactory = {
    val schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
    val parser        = SAXParserFactory.newInstance()
    val schemaUrl     = getClass.getResource(messageType.xsdPath)
    val schema        = schemaFactory.newSchema(schemaUrl)
    parser.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    parser.setFeature("http://xml.org/sax/features/external-general-entities", false)
    parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    parser.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    parser.setNamespaceAware(true)
    parser.setXIncludeAware(false)
    parser.setSchema(schema)
    parser
  }

  def validate(
    messageType: MessageType,
    xml: Source[ByteString, _]
  ): Future[Either[NonEmptyList[SchemaValidationError], Unit]] =
    parsersByType(messageType).map { saxParser =>
      val parser = saxParser.newSAXParser.getParser

      val errorBuffer: mutable.ListBuffer[SchemaValidationError] =
        new mutable.ListBuffer[SchemaValidationError]

      parser.setErrorHandler(new ErrorHandler {
        override def warning(error: SAXParseException): Unit = {}
        override def error(error: SAXParseException): Unit = {
          errorBuffer += SchemaValidationError.fromSaxParseException(error)
        }
        override def fatalError(error: SAXParseException): Unit = {
          errorBuffer += SchemaValidationError.fromSaxParseException(error)
        }
      })

      val xmlInput = xml.runWith(StreamConverters.asInputStream(20.seconds))

      val inputSource = new InputSource(xmlInput)

      val parseXml = Either
        .catchOnly[SAXParseException] {
          parser.parse(inputSource)
        }
        .leftMap { exc =>
          NonEmptyList.of(SchemaValidationError.fromSaxParseException(exc))
        }

      NonEmptyList
        .fromList(errorBuffer.toList)
        .map(Either.left)
        .getOrElse(parseXml)
    }
}
