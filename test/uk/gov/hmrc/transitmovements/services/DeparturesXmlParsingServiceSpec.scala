/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.transitmovements.services

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.fasterxml.aalto.WFCException
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovements.models.DeclarationData
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.models.errors.parse.ParseError

import java.nio.charset.StandardCharsets
import scala.xml.NodeSeq

class DeparturesXmlParsingServiceSpec extends AnyFreeSpec with ScalaFutures with Matchers with TestActorSystem with ScalaCheckPropertyChecks {

  val testEnrolmentEORI = EORINumber("TEST")

  val validXml: NodeSeq =
    <CC015C>
      <messageSender>GB1234</messageSender>
    </CC015C>

  val noSender: NodeSeq =
    <CC015C></CC015C>

  val twoSenders: NodeSeq =
    <CC015C>
      <messageSender>GB1234</messageSender>
      <messageSender>XI1234</messageSender>
    </CC015C>

  val incompleteXml: String =
    "<CC015C><messageSender>GB1234</messageSender>"

  val missingInnerTag: String =
    "<CC015C><messageSender>GB1234</CC015C>"

  val mismatchedTags: String =
    "<CC015C><messageSender>GB1234</messageReceiver></CC015C>"

  private def createStream(node: NodeSeq): Source[ByteString, _] = createStream(node.mkString)

  private def createStream(string: String): Source[ByteString, _] =
    Source.single(ByteString(string, StandardCharsets.UTF_8))

  "When handed an XML stream" - {
    val service = new DeparturesXmlParsingServiceImpl

    "if it is valid, return an appropriate Declaration Data" in {
      val source = createStream(validXml)

      val result = service.extractDeclarationData(testEnrolmentEORI, source)

      whenReady(result.value) {
        _ mustBe Right(DeclarationData(enrolmentEoriNumber = testEnrolmentEORI, movementEoriNumber = EORINumber("GB1234")))
      }
    }

    "if it doesn't have a message sender, return ParseError.NoElementFound" in {
      val source = createStream(noSender)

      val result = service.extractDeclarationData(testEnrolmentEORI, source)

      whenReady(result.value) {
        _ mustBe Left(ParseError.NoElementFound)
      }
    }

    "if it has two senders, return ParseError.TooManyElementsFound" in {
      val source = createStream(twoSenders)

      val result = service.extractDeclarationData(testEnrolmentEORI, source)

      whenReady(result.value) {
        _ mustBe Left(ParseError.TooManyElementsFound)
      }
    }

    "if it is missing the end tag, return ParseError.Unknown" in {
      val source = createStream(incompleteXml)

      val result = service.extractDeclarationData(testEnrolmentEORI, source)

      whenReady(result.value) {
        either =>
          either mustBe a[Left[ParseError, _]]
          either.left.get mustBe a[ParseError.Unknown]
          either.left.get.asInstanceOf[ParseError.Unknown].caughtException.get mustBe a[IllegalStateException]
      }
    }

    "if it is missing the end of an inner tag, return ParseError.Unknown" in {
      val source = createStream(missingInnerTag)

      val result = service.extractDeclarationData(testEnrolmentEORI, source)

      whenReady(result.value) {
        either =>
          either mustBe a[Left[ParseError, _]]
          either.left.get mustBe a[ParseError.Unknown]
          either.left.get.asInstanceOf[ParseError.Unknown].caughtException.get mustBe a[WFCException]
      }
    }

    "if it contains mismatched tags, return ParseError.Unknown" in {
      val source = createStream(mismatchedTags)

      val result = service.extractDeclarationData(testEnrolmentEORI, source)

      whenReady(result.value) {
        either =>
          either mustBe a[Left[ParseError, _]]
          either.left.get mustBe a[ParseError.Unknown]
          either.left.get.asInstanceOf[ParseError.Unknown].caughtException.get mustBe a[WFCException]
      }
    }
  }

}
