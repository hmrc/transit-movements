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

package uk.gov.hmrc.transitmovements.utils

import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.transitmovements.base.TestActorSystem

class PreMaterialisedFutureProviderSpec extends AnyFreeSpec with Matchers with ScalaFutures with TestActorSystem {

  val sut = new PreMaterialisedFutureProviderImpl

  "apply" - {
    "returns a future with the pre-materialised sink that completes when the stream completes" in {
      // Given this sink
      val sink = Sink.head[ByteString]

      // When we pre-materialise it via our helper object
      val (future, preMatSink) = sut(sink)

      // The the future should not be complete
      future.isCompleted mustBe false

      // When we attack the sink to a source without running
      val graph = Source.single(ByteString("1")).toMat(preMatSink)(Keep.right)

      // The the future should not be complete
      future.isCompleted mustBe false

      graph.run()

      // When we then run the stream
      whenReady(future) {
        _ => // if we get here, then things are good.
      }
    }
  }

}