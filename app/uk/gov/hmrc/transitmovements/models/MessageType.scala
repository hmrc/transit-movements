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

package uk.gov.hmrc.transitmovements.models

sealed trait MessageType extends Product with Serializable {
  def code: String
  def rootNode: String
  def xsdPath: String
}

sealed trait ArrivalMessageType extends MessageType

sealed trait DepartureMessageType extends MessageType

sealed trait RequestMessageType extends MessageType

sealed trait ResponseMessageType extends MessageType

sealed abstract class DepartureRequestMessageType(
  val code: String,
  val rootNode: String,
  val xsdPath: String
) extends RequestMessageType
    with DepartureMessageType

sealed abstract class DepartureResponseMessageType(
  val code: String,
  val rootNode: String,
  val xsdPath: String
) extends ResponseMessageType
    with DepartureMessageType

sealed abstract class ArrivalRequestMessageType(
  val code: String,
  val rootNode: String,
  val xsdPath: String
) extends RequestMessageType
    with ArrivalMessageType

sealed abstract class ArrivalResponseMessageType(
  val code: String,
  val rootNode: String,
  val xsdPath: String
) extends ResponseMessageType
    with ArrivalMessageType

sealed abstract class ErrorMessageType(val code: String, val rootNode: String, val xsdPath: String)
    extends ResponseMessageType
    with ArrivalMessageType
    with DepartureMessageType

object MessageType {
  // *******************
  // Departures Requests
  // *******************

  /** E_DEC_AMD (IE013) */
  case object DeclarationAmendment extends DepartureRequestMessageType("IE013", "CC013C", "/xsd/CC013C.xsd")

  /** E_DEC_INV (IE014) */
  case object DeclarationInvalidation extends DepartureRequestMessageType("IE014", "CC014C", "/xsd/CC014C.xsd")

  /** E_DEC_DAT (IE015) */
  case object DeclarationData extends DepartureRequestMessageType("IE015", "CC015C", "/xsd/CC015C.xsd")

  /** E_REQ_REL (IE054) */
  case object RequestOfRelease extends DepartureRequestMessageType("IE054", "CC054C", "/xsd/CC054C.xsd")

  /** E_PRE_NOT (IE170) */
  case object PresentationNotification extends DepartureRequestMessageType("IE170", "CC170C", "/xsd/CC170C.xsd")

  val departureRequestValues = Set(
    DeclarationAmendment,
    DeclarationInvalidation,
    DeclarationData,
    RequestOfRelease,
    PresentationNotification
  )

  // ********************
  // Departures Responses
  // ********************

  /** E_AMD_ACC (IE004) */
  case object AmendmentAcceptance extends DepartureResponseMessageType("IE004", "CC004C", "/xsd/CC004C.xsd")

  /** E_DEP_REJ (IE056) */
  case object DepartureOfficeRejection extends DepartureResponseMessageType("IE056", "CC056C", "/xsd/CC056C.xsd")

  /** E_INV_DEC (IE009) */
  case object InvalidationDecision extends DepartureResponseMessageType("IE009", "CC009C", "/xsd/CC009C.xsd")

  /** E_GUA_INV (IE055) */
  case object GuaranteeInvalid extends DepartureResponseMessageType("IE055", "CC055C", "/xsd/CC055C.xsd")

  /** E_DIS_SND (IE019) */
  case object Discrepancies extends DepartureResponseMessageType("IE019", "CC019C", "/xsd/CC019C.xsd")

  /** E_POS_ACK (IE928) */
  case object PositiveAcknowledge extends DepartureResponseMessageType("IE928", "CC928C", "/xsd/CC928C.xsd")

  /** E_MRN_ALL (IE028) */
  case object MrnAllocated extends DepartureResponseMessageType("IE028", "CC028C", "/xsd/CC028C.xsd")

  /** E_REL_TRA (IE029) */
  case object ReleaseForTransit extends DepartureResponseMessageType("IE029", "CC029C", "/xsd/CC029C.xsd")

  /** E_WRT_NOT (IE045) */
  case object WriteOffNotification extends DepartureResponseMessageType("IE045", "CC045C", "/xsd/CC045C.xsd")

  /** E_REL_NOT (IE051) */
  case object NoReleaseForTransit extends DepartureResponseMessageType("IE051", "CC051C", "/xsd/CC051C.xsd")

  /** E_CTR_DEC (IE060) */
  case object ControlDecisionNotification extends DepartureResponseMessageType("IE060", "CC060C", "/xsd/CC060C.xsd")

  /** E_AMD_NOT (IE022) */
  case object NotificationToAmend extends DepartureResponseMessageType("IE022", "CC022C", "/xsd/CC022C.xsd")

  /** E_INC_NOT (IE182) */
  case object IncidentNotification extends DepartureResponseMessageType("IE182", "CC182C", "/xsd/CC182C.xsd")

  val departureResponseValues = Set(
    AmendmentAcceptance,
    DepartureOfficeRejection,
    InvalidationDecision,
    GuaranteeInvalid,
    Discrepancies,
    PositiveAcknowledge,
    MrnAllocated,
    ReleaseForTransit,
    WriteOffNotification,
    NoReleaseForTransit,
    ControlDecisionNotification,
    NotificationToAmend,
    IncidentNotification
  )

  val departureValues = departureRequestValues ++ departureResponseValues

  // ****************
  // Arrival Requests
  // ****************

  /** E_REQ_REL (IE054) */
  case object ArrivalNotification extends ArrivalRequestMessageType("IE007", "CC007C", "/xsd/CC007C.xsd")

  /** E_PRE_NOT (IE170) */
  case object UnloadingRemarks extends ArrivalRequestMessageType("IE044", "CC044C", "/xsd/CC044C.xsd")

  val arrivalRequestValues = Set(
    ArrivalNotification,
    UnloadingRemarks
  )

  // ****************
  // Arrival Response
  // ****************

  /** E_DES_REJ (IE057) */
  case object DestinationOfficeRejection extends ArrivalResponseMessageType("IE057", "CC057C", "/xsd/CC057C.xsd")

  /** E_GDS_REL (IE025) */
  case object GoodsReleaseNotification extends ArrivalResponseMessageType("IE025", "CC025C", "/xsd/CC025C.xsd")

  /** E_ULD_PER (IE025) */
  case object UnloadingPermission extends ArrivalResponseMessageType("IE043", "CC043C", "/xsd/CC043C.xsd")

  val arrivalResponseValues = Set(
    DestinationOfficeRejection,
    GoodsReleaseNotification,
    UnloadingPermission
  )

  val arrivalValues = arrivalRequestValues ++ arrivalResponseValues

  // ***************
  // Error Responses
  // ***************

  case object XmlNack extends ErrorMessageType("IE917", "CC917C", "/xsd/CC917C.xsd")

  val errorValues = Set(XmlNack)

  val requestValues = arrivalRequestValues ++ departureRequestValues

  val responseValues = arrivalResponseValues ++ departureResponseValues

  val values = arrivalValues ++ departureValues ++ errorValues

  def withCode(name: String): Option[MessageType] =
    values.find(_.code == name)
}
