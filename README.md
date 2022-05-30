
# transit-movements

This is a microservice for an internal API to store, retrieve and forward a traders submitted arrival and departure information.

This microservice is in [Beta](https://www.gov.uk/help/beta). The signature may be subject to change.


### Prerequisites
- Scala 2.12.11
- Java 8
- sbt > 1.3.13
- [Service Manager](https://github.com/hmrc/service-manager)

### Development Setup

Run from the console using: `sbt run`

To run the whole stack, using service manager: `sm --start CTC_TRADERS_API`

Task | Description | Command
:-------|:------------|:-----
test | Runs the standard unit tests | ```$ sbt test```
it:test  | Runs the integration tests | ```$ sbt it:test ```
dependencyCheck | Runs dependency-check against the current project. It aggregates dependencies and generates a report | ```$ sbt dependencyCheck```
dependencyUpdates |  Shows a list of project dependencies that can be updated | ```$ sbt dependencyUpdates```
dependencyUpdatesReport | Writes a list of project dependencies to a file | ```$ sbt dependencyUpdatesReport```

### CTC Traders API related documentation

- [CTC Traders API specifications](https://developer.service.hmrc.gov.uk/api-documentation/docs/api/service/common-transit-convention-traders/2.0) lets you see a list of our endpoints.

### Helpful information

You can find helpful guides on the [HMRC Developer Hub](https://developer.service.hmrc.gov.uk/api-documentation/docs/using-the-hub).

### Reporting Issues

You can create a [GitHub issue](https://github.com/hmrc/common-transit-convention-traders/issues). Alternatively you can contact our Software Development Support team. Email them at sdsteam@hmr.gov.uk, to receive a form where you can add details about your requirements and questions.


### License

This code is open source software licensed under the [Apache 2.0 ```License]("http://www.apache.org/licenses/LICENSE-2.0.html").