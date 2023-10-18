package uk.gov.hmrc.transitmovements.models.mongo

import uk.gov.hmrc.transitmovements.models.TotalCount

case class MongoPaginatedMovements(totalCount: TotalCount, movementSummary: Vector[MongoMovement])
