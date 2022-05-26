package uk.gov.hmrc.transitmovements.services

import cats.data.EitherT
import com.google.inject.ImplementedBy
import uk.gov.hmrc.transitmovements.models.{DeclarationData, Departure, DepartureId, EORINumber}
import uk.gov.hmrc.transitmovements.repositories.DeparturesRepository

import java.time.{Clock, OffsetDateTime, ZoneOffset}
import javax.inject.Inject
import scala.concurrent.Future


@ImplementedBy(classOf[DeparturesServiceImpl])
trait DeparturesService {
  def insertDeparture(declarationData: DeclarationData): EitherT[Future, Error, Departure]
}



@Singleton
class DeparturesServiceImpl @Inject()(repository: DeparturesRepository, clock: Clock) extends DeparturesService {

  override def insertDeparture(declarationData: DeclarationData): EitherT[Future, Error, DeclarationResponse] = {
    val departure = createDeparture(declarationData)
    repository.insert(departure)
  }


  val createDeparture: DeclarationData => Departure = {
    (declarationData) =>
      Departure(
        DepartureId(""),
        enrollmentEORINumber = EORINumber("111"),
        movementEORINumber = declarationData.movementEoriNumber,
        movementReferenceNumber = None,
        created = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC),
        updated = OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC),
        messages = Seq.empty
      )
  }

}
