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

package uk.gov.hmrc.transitmovements.config

import uk.gov.hmrc.internalauth.client.IAAction
import uk.gov.hmrc.internalauth.client.Predicate
import uk.gov.hmrc.internalauth.client.Resource
import uk.gov.hmrc.internalauth.client.ResourceLocation
import uk.gov.hmrc.internalauth.client.ResourceType

object Constants {

  val ObjectStoreURI = "X-Object-Store-Uri"
  val MessageType    = "X-Message-Type"

  val ObjectStoreOwner = "transit-movements"

  val APIVersionHeaderKey: String        = "APIVersion"
  val APIVersionFinalHeaderValue: String = "final"

  object Predicates {

    private val resourceType      = ResourceType("transit-movements")
    private val movementsResource = Resource(resourceType, ResourceLocation("movements"))
    private val messagesResource  = Resource(resourceType, ResourceLocation("movements/messages"))
    private val statusResource    = Resource(resourceType, ResourceLocation("movements/messages/status"))

    private val read  = IAAction("READ")
    private val write = IAAction("WRITE")

    val READ_MOVEMENT: Predicate.Permission  = Predicate.Permission(movementsResource, read)
    val WRITE_MOVEMENT: Predicate.Permission = Predicate.Permission(movementsResource, write)
    val READ_MESSAGE: Predicate.Permission   = Predicate.Permission(messagesResource, read)
    val WRITE_MESSAGE: Predicate.Permission  = Predicate.Permission(messagesResource, write)
    val WRITE_STATUS: Predicate.Permission   = Predicate.Permission(statusResource, write)

  }

}
