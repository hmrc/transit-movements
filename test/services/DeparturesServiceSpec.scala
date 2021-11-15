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

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.util.ByteString
import base.TestActorSystem
import cats.data.NonEmptyList
import com.mongodb.reactivestreams.client.ClientSession
import config.AppConfig
import connectors.FakeEisRouterConnector
import models.MessageType
import models.errors.SchemaValidationError
import models.errors.UpstreamServiceError
import models.errors.XmlValidationError
import models.values.DepartureId
import models.values.EoriNumber
import models.values.MessageId
import org.mockito.scalatest.AsyncIdiomaticMockito
import org.mongodb.scala.MongoClient
import org.mongodb.scala._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import play.api.http.ContentTypes
import repositories.FakeDeparturesRepository
import repositories.FakeMessagesRepository
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.RetentionPeriod
import uk.gov.hmrc.objectstore.client.config.ObjectStoreClientConfig
import uk.gov.hmrc.objectstore.client.play.Implicits._
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClientEither
import uk.gov.hmrc.objectstore.client.play.test.stub
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.time.Clock
import java.util.UUID
import scala.concurrent.Future

class DeparturesServiceSpec
  extends AsyncFlatSpec
  with Matchers
  with AsyncIdiomaticMockito
  with BeforeAndAfterEach
  with TestActorSystem {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  val mongoComponent  = mock[MongoComponent]
  val mongoClient     = mock[MongoClient]
  val clientSession   = mock[ClientSession]
  val mockObjectStore = mock[PlayObjectStoreClientEither]

  override protected def beforeEach(): Unit = {
    reset(mongoComponent, mongoClient, clientSession)
    mongoComponent.client returns mongoClient
    mongoClient.startSession(any[ClientSessionOptions]) returns SingleObservable(clientSession)
    clientSession.hasActiveTransaction().returns(true, false)
    clientSession.commitTransaction() returns SingleObservable(null.asInstanceOf[Void])
    clientSession.abortTransaction() returns SingleObservable(null.asInstanceOf[Void])
  }

  def mkAppConfig(config: Configuration) = {
    val servicesConfig = new ServicesConfig(config)
    new AppConfig(config, servicesConfig)
  }

  val baseUrl           = s"baseUrl-${UUID.randomUUID}"
  val owner             = s"owner-${UUID.randomUUID}"
  val token             = s"token-${UUID.randomUUID}"
  val objectStoreConfig = ObjectStoreClientConfig(baseUrl, owner, token, RetentionPeriod.OneYear)

  def service(
    insertDepartureResponse: () => Future[DepartureId] = () =>
      Future.failed(new NotImplementedError),
    insertMessageResponse: () => Future[MessageId] = () => Future.failed(new NotImplementedError),
    sendMessageResponse: () => Future[Either[UpstreamErrorResponse, Unit]] = () =>
      Future.failed(new NotImplementedError),
    validator: XmlValidationService = FakeXmlValidationService(),
    appConfig: AppConfig = mkAppConfig(
      Configuration("object-store.default-directory" -> "messages")
    ),
    objectStore: PlayObjectStoreClientEither =
      new stub.StubPlayObjectStoreClientEither(objectStoreConfig)
  ) = new DeparturesServiceImpl(
    FakeDeparturesRepository(insertDepartureResponse),
    FakeMessagesRepository(insertMessageResponse),
    objectStore,
    FakeEisRouterConnector(sendMessageResponse),
    validator,
    new DeparturesXmlParsingServiceImpl,
    appConfig,
    Clock.systemUTC(),
    mongoComponent
  )

  val departureId = DepartureId(DepartureId.fromHex("617b162bb367d2bc"))
  val messageId   = MessageId(MessageId.fromHex("617fce4ed2a774d7"))

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

  "DeparturesService.sendDeclarationData" should "return departure ID when everything is successful" in {
    service(
      insertDepartureResponse = () => Future.successful(departureId),
      insertMessageResponse = () => Future.successful(messageId),
      sendMessageResponse = () => Future.successful(Right(()))
    ).sendDeclarationData(EoriNumber("GB123456789000"), validDeclarationDataStream).map { result =>
      clientSession.commitTransaction() wasCalled once
      clientSession.abortTransaction() wasNever called
      result shouldBe Right(departureId)
    }
  }

  it should "return UpstreamServiceError when there is a client error sending to EIS" in {
    val clientError = UpstreamErrorResponse("Argh!!!", 400)
    service(
      insertDepartureResponse = () => Future.successful(departureId),
      insertMessageResponse = () => Future.successful(messageId),
      sendMessageResponse = () => Future.successful(Left(clientError))
    ).sendDeclarationData(EoriNumber("GB123456789000"), validDeclarationDataStream).map { result =>
      clientSession.abortTransaction() wasCalled once
      clientSession.commitTransaction() wasNever called
      result shouldBe Left(UpstreamServiceError.causedBy(clientError))
    }
  }

  it should "return XmlValidationError when the XML fails schema validation" in {
    val validationErrors = NonEmptyList.of(
      SchemaValidationError(0, 1, "Value 'ABC12345' is not facet-valid with respect to pattern"),
      SchemaValidationError(2, 3, "The value 'ABC12345' of element 'DatOfPreMES9' is not valid")
    )

    service(
      insertDepartureResponse = () => Future.successful(departureId),
      insertMessageResponse = () => Future.successful(messageId),
      sendMessageResponse = () => Future.successful(Right(())),
      validator = FakeXmlValidationService(() => Future.successful(Left(validationErrors)))
    ).sendDeclarationData(EoriNumber("GB123456789000"), validDeclarationDataStream).map { result =>
      clientSession.abortTransaction() wasCalled once
      clientSession.commitTransaction() wasNever called
      result shouldBe Left(XmlValidationError(MessageType.DeclarationData, validationErrors))
    }
  }

  // FIXME: It doesn't seem possible to mock object-store's default arguments and it doesn't expose an interface to write a fake against.
  // At the moment this throws NPE because the default parameters to object-store methods use the ObjectStoreConfig, which is a private
  // member of the object-store client which we can't mock.
  // We also can't use the stub object-store client because we want to simulate a failure for the purposes of this test.
  ignore should "return UpstreamServiceError when there is a client error saving to object-store" in {
    val clientError = UpstreamErrorResponse("Argh!!!", 400)

    mockObjectStore
      .putObject[Source[ByteString, NotUsed]](
        any[Path.File],
        any[Source[ByteString, NotUsed]],
        any[RetentionPeriod],
        contentType = Some(ContentTypes.XML),
        any[String]
      )
      .returns(Future.successful(Left(clientError)))

    service(
      insertDepartureResponse = () => Future.successful(departureId),
      insertMessageResponse = () => Future.successful(messageId),
      sendMessageResponse = () => Future.successful(Right(())),
      objectStore = mockObjectStore
    ).sendDeclarationData(EoriNumber("GB123456789000"), validDeclarationDataStream).map { result =>
      clientSession.abortTransaction() wasCalled once
      clientSession.commitTransaction() wasNever called
      result shouldBe Left(UpstreamServiceError.causedBy(clientError))
    }
  }
}
