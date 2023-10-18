package uk.gov.hmrc.transitmovements.models.mongo

import uk.gov.hmrc.transitmovements.models.TotalCount

case class MongoPaginatedMessages(totalCount: TotalCount, messageSummary: Vector[MongoMessage])

