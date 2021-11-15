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

import akka.stream.scaladsl.Source
import akka.util.ByteString
import base.TestActorSystem
import models.errors.TransitMovementError
import models.request.DeclarationDataRequest
import models.values.CustomsOfficeNumber
import models.values.EoriNumber
import models.values.LocalReferenceNumber
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.OffsetDateTime
import java.time.ZoneOffset

class DeparturesXmlParsingServiceSpec
  extends AnyFlatSpec
  with Matchers
  with EitherValues
  with ScalaFutures
  with TestActorSystem {

  val validDeclarationDataXml =
    <ncts:CC015C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">
      <messageSender>eeNombfsgLrHStH</messageSender>
      <messageRecipient>4RP1BBcmwtvy6cPU5Pv1iL9RLPbumm</messageRecipient>
      <preparationDateAndTime>2021-02-01T07:06:15</preparationDateAndTime>
      <messageIdentification>wL4wO</messageIdentification>
      <messageType>CC015C</messageType>
      <TransitOperation>
        <LRN>N0bl</LRN>
        <declarationType>ftv</declarationType>
        <additionalDeclarationType>V</additionalDeclarationType>
        <security>2</security>
        <reducedDatasetIndicator>0</reducedDatasetIndicator>
        <bindingItinerary>1</bindingItinerary>
      </TransitOperation>
      <CustomsOfficeOfDeparture>
        <referenceNumber>TEVDFNTL</referenceNumber>
      </CustomsOfficeOfDeparture>
      <CustomsOfficeOfDestinationDeclared>
        <referenceNumber>TEVDFNTL</referenceNumber>
      </CustomsOfficeOfDestinationDeclared>
      <HolderOfTheTransitProcedure>
      </HolderOfTheTransitProcedure>
      <Guarantee>
        <sequenceNumber>69488</sequenceNumber>
        <guaranteeType>V</guaranteeType>
      </Guarantee>
      <Consignment>
        <grossMass>5686015928.255290</grossMass>
        <HouseConsignment>
          <sequenceNumber>69488</sequenceNumber>
          <grossMass>5686015928.255290</grossMass>
          <ConsignmentItem>
            <goodsItemNumber>65494</goodsItemNumber>
            <declarationGoodsItemNumber>19166</declarationGoodsItemNumber>
            <Commodity>
              <descriptionOfGoods>b5YBKA0x6zmXPb1nlSGUqsSv4tN2L8bLHWTAmAMqpR4D0y5wkmjjDV3PmOSyxqbDlmj7ep2q4I6KaRLhdwxkiyggXtZph5k3xp7o6AMGMWJd9X9F2JGnTsFaCGgThAC2QrJ4XQ3T9cHUw10QSPZTfQXKCvyRNxvCtJClXnMegxKC4R7YVerjYqLMYTpK1iYPqKRsktceQMJv3RWQeLovCr9FFwLzmAu7B8wxD2iurW55mLlPXgIkEH1yIVkM3rCLHiuhvNKiZxGQ0NaXxKei2UHBxBmYXq7M4fsoWfTSGnmxIlG5b9f58ZvadvuYvWTwRf</descriptionOfGoods>
            </Commodity>
            <Packaging>
              <sequenceNumber>69488</sequenceNumber>
              <typeOfPackages>Wt</typeOfPackages>
            </Packaging>
          </ConsignmentItem>
        </HouseConsignment>
      </Consignment>
    </ncts:CC015C>

  val validDeclarationDataStream = Source.single(ByteString(validDeclarationDataXml.toString))

  val missingPrepDateTimeXml =
    <ncts:CC015C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">
      <messageSender>eeNombfsgLrHStH</messageSender>
      <messageRecipient>4RP1BBcmwtvy6cPU5Pv1iL9RLPbumm</messageRecipient>
      <messageIdentification>wL4wO</messageIdentification>
      <messageType>CC015C</messageType>
      <TransitOperation>
        <LRN>N0bl</LRN>
        <declarationType>ftv</declarationType>
        <additionalDeclarationType>V</additionalDeclarationType>
        <security>2</security>
        <reducedDatasetIndicator>0</reducedDatasetIndicator>
        <bindingItinerary>1</bindingItinerary>
      </TransitOperation>
      <CustomsOfficeOfDeparture>
        <referenceNumber>TEVDFNTL</referenceNumber>
      </CustomsOfficeOfDeparture>
      <CustomsOfficeOfDestinationDeclared>
        <referenceNumber>TEVDFNTL</referenceNumber>
      </CustomsOfficeOfDestinationDeclared>
      <HolderOfTheTransitProcedure>
      </HolderOfTheTransitProcedure>
      <Guarantee>
        <sequenceNumber>69488</sequenceNumber>
        <guaranteeType>V</guaranteeType>
      </Guarantee>
      <Consignment>
        <grossMass>5686015928.255290</grossMass>
        <HouseConsignment>
          <sequenceNumber>69488</sequenceNumber>
          <grossMass>5686015928.255290</grossMass>
          <ConsignmentItem>
            <goodsItemNumber>65494</goodsItemNumber>
            <declarationGoodsItemNumber>19166</declarationGoodsItemNumber>
            <Commodity>
              <descriptionOfGoods>b5YBKA0x6zmXPb1nlSGUqsSv4tN2L8bLHWTAmAMqpR4D0y5wkmjjDV3PmOSyxqbDlmj7ep2q4I6KaRLhdwxkiyggXtZph5k3xp7o6AMGMWJd9X9F2JGnTsFaCGgThAC2QrJ4XQ3T9cHUw10QSPZTfQXKCvyRNxvCtJClXnMegxKC4R7YVerjYqLMYTpK1iYPqKRsktceQMJv3RWQeLovCr9FFwLzmAu7B8wxD2iurW55mLlPXgIkEH1yIVkM3rCLHiuhvNKiZxGQ0NaXxKei2UHBxBmYXq7M4fsoWfTSGnmxIlG5b9f58ZvadvuYvWTwRf</descriptionOfGoods>
            </Commodity>
            <Packaging>
              <sequenceNumber>69488</sequenceNumber>
              <typeOfPackages>Wt</typeOfPackages>
            </Packaging>
          </ConsignmentItem>
        </HouseConsignment>
      </Consignment>
    </ncts:CC015C>

  val missingPrepDateTimeDataStream = Source.single(ByteString(missingPrepDateTimeXml.toString))

  val service = new DeparturesXmlParsingServiceImpl

  val eoriNumber = EoriNumber("GB123456789000")

  "DeparturesParsingService" should "extract required values from declaration data message" in {
    val result = service
      .parseDeclarationDataRequest(
        eoriNumber,
        validDeclarationDataStream
      )
      .futureValue
      .value

    result shouldBe DeclarationDataRequest(
      eoriNumber,
      OffsetDateTime.of(2021, 2, 1, 7, 6, 15, 0, ZoneOffset.UTC),
      LocalReferenceNumber("N0bl"),
      CustomsOfficeNumber("TEVDFNTL")
    )
  }

  it should "return an error when required data cannot be parsed from the XML" in {
    val result = service
      .parseDeclarationDataRequest(
        eoriNumber,
        missingPrepDateTimeDataStream
      )
      .futureValue
      .left
      .value

    result shouldBe TransitMovementError.badRequestError(
      "Unable to parse required values from IE015 message"
    )
  }
}
