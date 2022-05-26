package uk.gov.hmrc.transitmovements.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.JsString
import play.api.libs.json.Json
import java.net.URI

import java.time.OffsetDateTime
import uk.gov.hmrc.transitmovements.models.formats.MongoFormats._

class MovementSpec extends AnyFlatSpec with Matchers {

  "json movement message" should "be created correctly" in {
    val movement = Movement(
      MovementId("1"),
      EORINumber("222"),
      EORINumber("223"),
      Some(MovementReferenceNumber("333")),
      OffsetDateTime.now(),
      OffsetDateTime.now(),
      Seq(
        MovementMessage(
          id = MovementMessageId("999"),
          received = OffsetDateTime.now(),
          generated = OffsetDateTime.now(),
          messageType = MessageType.ReleaseForTransit,
          triggerId = Some(TriggerId("888")),
          url = Some(URI.create("xyz")),
          None
        )
      )
    )

    val result = Json.toJson[Movement](movement)

    (result \ "movementEORINumber").get should be(JsString("223"))
    (result \ "movementReferenceNumber").get should be(JsString("333"))
  }
}
