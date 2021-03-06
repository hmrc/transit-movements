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

package uk.gov.hmrc.transitmovements.controllers.stream

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.FileIO
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalatest.matchers.must.Matchers
import play.api.http.HeaderNames
import play.api.http.Status.OK
import play.api.libs.Files
import play.api.libs.Files.SingletonTemporaryFileCreator
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.BaseController
import play.api.mvc.ControllerComponents
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers.contentAsString
import play.api.test.Helpers.defaultAwaitTimeout
import play.api.test.Helpers.status
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.transitmovements.base.SpecBase
import uk.gov.hmrc.transitmovements.base.TestActorSystem

import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class StreamingParsersSpec extends SpecBase with Matchers with TestActorSystem {

  lazy val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> "text/plain", HeaderNames.ACCEPT -> "application/vnd.hmrc.2.0+json"))

  class Harness(val controllerComponents: ControllerComponents = stubControllerComponents())(implicit val materializer: Materializer)
      extends BaseController
      with StreamingParsers {

    def testFromMemory: Action[Source[ByteString, _]] = Action.async(streamFromMemory) {
      request => result.apply(request).run(request.body)(materializer)
    }

    def result: Action[String] = Action.async(parse.text) {
      request =>
        Future.successful(Ok(request.body))
    }

    def testFile: Action[Files.TemporaryFile] = Action.async(parse.temporaryFile) {
      implicit request =>
        def stream() = streamFromTemporaryFile {
          source =>
            source.toMat(
              Sink.fold("")(
                (current: String, in: ByteString) => current + in.decodeString(StandardCharsets.UTF_8)
              )
            )(Keep.right)
        }

        for {
          firstString  <- stream()
          secondString <- stream()
        } yield Ok(Json.obj("first" -> firstString, "second" -> secondString))
    }
  }

  @tailrec
  private def generateByteString(kb: Int, accumulator: ByteString = ByteString.empty): ByteString =
    if (kb <= 0) accumulator
    else {
      lazy val valueAsByte: Byte = (kb % 10).toString.getBytes(StandardCharsets.UTF_8)(0) // one byte each
      generateByteString(kb - 1, ByteString.fromArray(Array.fill(1024)(valueAsByte)) ++ accumulator)
    }

  private def generateSource(byteString: ByteString): Source[ByteString, NotUsed] =
    Source(byteString.grouped(1024).to[immutable.Iterable])

  "Streaming" - {
    "from Memory" - {
      (1 to 5).foreach {
        value =>
          s"~$value kb string is created" in {
            val byteString = generateByteString(value)
            val request    = FakeRequest("POST", "/", headers, generateSource(byteString))
            val sut        = new Harness()
            val result     = sut.testFromMemory()(request)
            status(result) mustBe OK
            contentAsString(result) mustBe byteString.decodeString(StandardCharsets.UTF_8)
          }
      }
    }
  }

  "From a temporary file" - {

    "test that we can stream from it multiple times" in {
      val file: Files.TemporaryFile = SingletonTemporaryFileCreator.create()
      try {
        import scala.concurrent.ExecutionContext.Implicits.global

        val byteString     = generateByteString(1)
        val expectedString = byteString.decodeString(StandardCharsets.UTF_8)
        Await.result(
          generateSource(byteString).runWith(FileIO.toPath(file.path)).map {
            _ =>
              val request = FakeRequest("POST", "/", headers, file)
              val sut     = new Harness()
              val result  = sut.testFile()(request)
              status(result) mustBe OK
              Json.parse(contentAsString(result)) mustBe Json.obj("first" -> expectedString, "second" -> expectedString)
          },
          5.seconds
        )
      } finally file.delete()
    }
  }

}
