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

package uk.gov.hmrc.transitmovements.controllers

import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import akka.stream.Materializer
import akka.stream.scaladsl.FileIO
import play.api.Logging
import play.api.mvc.ControllerComponents
import play.api.mvc.Result
import play.api.libs.json.Json
import play.api.libs.Files.TemporaryFileCreator
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.http.HeaderCarrierConverter
import uk.gov.hmrc.transitmovements.controllers.errors.ConvertError
import uk.gov.hmrc.transitmovements.controllers.stream.StreamingParsers
import uk.gov.hmrc.transitmovements.models.EORINumber
import uk.gov.hmrc.transitmovements.services.DeparturesService
import uk.gov.hmrc.transitmovements.services.DeparturesXmlParsingService

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeparturesController @Inject() (
  cc: ControllerComponents,
  departuresService: DeparturesService,
  xmlParsingService: DeparturesXmlParsingService,
  val temporaryFileCreator: TemporaryFileCreator
)(implicit
  val materializer: Materializer
) extends BackendController(cc)
    with Logging
    with StreamingParsers
    with ConvertError
    with TemporaryFiles {

  def post(eori: EORINumber) = Action.async(streamFromMemory) {
    implicit request =>
      implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
      withTemporaryFile {
        (temporaryFile, source) =>
          (for {
            declarationData <- xmlParsingService.extractDeclarationData(source).asPresentation
            fileSource = FileIO.fromPath(temporaryFile)
            declarationResponse <- departuresService.create(eori, declarationData, fileSource).asPresentation
          } yield declarationResponse).fold[Result](
            baseError => Status(baseError.code.statusCode)(Json.toJson(baseError)),
            response => Ok(Json.toJson(response))
          )
      }.toResult
  }

}
