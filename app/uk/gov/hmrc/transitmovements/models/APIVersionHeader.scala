package uk.gov.hmrc.transitmovements.models

enum APIVersionHeader(val value: String, val path: String) {
  case V2_1 extends APIVersionHeader("2.1", "v2_1")
  case V3_0 extends APIVersionHeader("3.0", "v3_0")
}

object APIVersionHeader {
  def fromString(value: String): Option[APIVersionHeader] =
    values.find(_.value == value)
}
