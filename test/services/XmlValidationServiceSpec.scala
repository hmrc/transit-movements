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

import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import base.TestActorSystem
import models.MessageType
import org.scalatest.EitherValues
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class XmlValidationServiceSpec
  extends AnyFlatSpec
  with Matchers
  with EitherValues
  with ScalaFutures
  with IntegrationPatience
  with TestActorSystem {

  val service = new XmlValidationServiceImpl

  val validAmendmentXml =
    """
      |<ncts:CC004C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">
      |  <messageSender>NTA.GB</messageSender>
      |  <messageRecipient>MDTP-DEP-617c1a0a9499d726</messageRecipient>
      |  <preparationDateAndTime>2021-10-29T17:20:02</preparationDateAndTime>
      |  <messageIdentification>617c1a0a9499d726</messageIdentification>
      |  <messageType>CC004C</messageType>
      |  <TransitOperation>
      |    <MRN>20GB00006010024812</MRN>
      |    <amendmentSubmissionDateAndTime>2021-10-29T17:16:04</amendmentSubmissionDateAndTime>
      |    <amendmentAcceptanceDateAndTime>2021-10-29T17:19:12</amendmentAcceptanceDateAndTime>
      |  </TransitOperation>
      |  <CustomsOfficeOfDeparture>
      |    <referenceNumber>GB000060</referenceNumber>
      |  </CustomsOfficeOfDeparture>
      |  <HolderOfTheTransitProcedure>
      |    <identificationNumber>GB954131533000</identificationNumber>
      |    <name>NCTS UK TEST LAB HMCE</name>
      |    <Address>
      |      <streetAndNumber>11TH FLOOR, ALEX HOUSE, VICTORIA AV</streetAndNumber>
      |      <postcode>SS99 1AA</postcode>
      |      <city>SOUTHEND-ON-SEA, ESSEX</city>
      |      <country>GB</country>
      |    </Address>
      |  </HolderOfTheTransitProcedure>
      |</ncts:CC004C>
      |""".stripMargin

  val amendmentMissingMessageTypeXml =
    """
      |<ncts:CC004C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">
      |  <messageSender>NTA.GB</messageSender>
      |  <messageRecipient>MDTP-DEP-617c1a0a9499d726</messageRecipient>
      |  <preparationDateAndTime>2021-10-29T17:20:02</preparationDateAndTime>
      |  <messageIdentification>617c1a0a9499d726</messageIdentification>
      |  <TransitOperation>
      |    <MRN>20GB00006010024812</MRN>
      |    <amendmentSubmissionDateAndTime>2021-10-29T17:16:04</amendmentSubmissionDateAndTime>
      |    <amendmentAcceptanceDateAndTime>2021-10-29T17:19:12</amendmentAcceptanceDateAndTime>
      |  </TransitOperation>
      |  <CustomsOfficeOfDeparture>
      |    <referenceNumber>GB000060</referenceNumber>
      |  </CustomsOfficeOfDeparture>
      |  <HolderOfTheTransitProcedure>
      |    <identificationNumber>GB954131533000</identificationNumber>
      |    <name>NCTS UK TEST LAB HMCE</name>
      |    <Address>
      |      <streetAndNumber>11TH FLOOR, ALEX HOUSE, VICTORIA AV</streetAndNumber>
      |      <postcode>SS99 1AA</postcode>
      |      <city>SOUTHEND-ON-SEA, ESSEX</city>
      |      <country>GB</country>
      |    </Address>
      |  </HolderOfTheTransitProcedure>
      |</ncts:CC004C>
      |""".stripMargin

  val amendmentInvalidDateXml =
    """
      |<ncts:CC004C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">
      |  <messageSender>NTA.GB</messageSender>
      |  <messageRecipient>MDTP-DEP-617c1a0a9499d726</messageRecipient>
      |  <preparationDateAndTime>ABC12345</preparationDateAndTime>
      |  <messageIdentification>617c1a0a9499d726</messageIdentification>
      |  <messageType>CC004C</messageType>
      |  <TransitOperation>
      |    <MRN>20GB00006010024812</MRN>
      |    <amendmentSubmissionDateAndTime>2021-10-29T17:16:04</amendmentSubmissionDateAndTime>
      |    <amendmentAcceptanceDateAndTime>2021-10-29T17:19:12</amendmentAcceptanceDateAndTime>
      |  </TransitOperation>
      |  <CustomsOfficeOfDeparture>
      |    <referenceNumber>GB000060</referenceNumber>
      |  </CustomsOfficeOfDeparture>
      |  <HolderOfTheTransitProcedure>
      |    <identificationNumber>GB954131533000</identificationNumber>
      |    <name>NCTS UK TEST LAB HMCE</name>
      |    <Address>
      |      <streetAndNumber>11TH FLOOR, ALEX HOUSE, VICTORIA AV</streetAndNumber>
      |      <postcode>SS99 1AA</postcode>
      |      <city>SOUTHEND-ON-SEA, ESSEX</city>
      |      <country>GB</country>
      |    </Address>
      |  </HolderOfTheTransitProcedure>
      |</ncts:CC004C>
      |""".stripMargin

  val toStringSink = Sink.fold[String, ByteString]("")((s, bs) => s + bs.decodeString("UTF-8"))

  "XmlValidationService" should "validate valid XML successfully" in {
    val result =
      service
        .validate(
          MessageType.AmendmentAcceptance,
          Source.single(ByteString(validAmendmentXml))
        )
        .futureValue

    result shouldBe a[Right[_, _]]
  }

  it should "return an error when the XML document is empty" in {
    val result = service
      .validate(
        MessageType.AmendmentAcceptance,
        Source.empty[ByteString]
      )
      .futureValue

    result shouldBe a[Left[_, _]]

    val errors = result.left.value.toList

    errors should have length 1

    errors.head.message should include("Premature end of file")
  }

  it should "return an error when there is a missing field" in {
    val result = service
      .validate(
        MessageType.AmendmentAcceptance,
        Source.single(ByteString.fromString(amendmentMissingMessageTypeXml))
      )
      .futureValue

    result shouldBe a[Left[_, _]]

    val errors = result.left.value.toList

    errors should have length 1

    errors.head.message should include("One of '{messageType}' is expected")
  }

  it should "return an error when the data is of incorrect type" in {
    val result = service
      .validate(
        MessageType.AmendmentAcceptance,
        Source.single(ByteString.fromString(amendmentInvalidDateXml))
      )
      .futureValue

    result shouldBe a[Left[_, _]]

    val errors = result.left.value.toList

    errors should have length 2

    errors(0).message should include("Value 'ABC12345' is not facet-valid with respect to pattern")

    errors(1).message should include(
      "The value 'ABC12345' of element 'preparationDateAndTime' is not valid"
    )
  }
}
