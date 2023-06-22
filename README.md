
# transit-movements

This is a microservice for an internal API to store, retrieve and forward a traders submitted arrival and departure information.

This microservice is in [Beta](https://www.gov.uk/help/beta). The signature may be subject to change.


### Prerequisites
- Scala 2.13.8
- Java 8
- sbt 1.7.2
- [Service Manager](https://github.com/hmrc/sm2)

### Development Setup

Run from the console using: `sbt run`. To run the whole stack, using service manager: `sm2 --start CTC_TRADERS_API`.

**On first time setup**, if you wish to test functionality of storing messages in `object-store` (by default, messages over
0.5MB), you will need to ensure that an appropriate internal auth token is generated. If you run the service via 
service manager, send the following payload to `POST http://localhost:8470/test-only/token`:

```json
{
    "token": "transit-movements-token",
    "principal": "transit-movements",
    "permissions": [{
        "resourceType": "object-store",
        "resourceLocation": "transit-movements",
        "actions": ["READ", "WRITE"]
    }]
}
```

The following sbt tasks are available on this project

| Task                    | Description                                                                                          | Command                                        |
|:------------------------|:-----------------------------------------------------------------------------------------------------|:-----------------------------------------------|
| test                    | Runs the standard unit tests                                                                         | ```$ sbt test```                               |
| it:test                 | Runs the integration tests                                                                           | ```$ sbt it:test ```                           |
| scalafmt                | Runs the scala formatter (on project/tests/integration tests)                                        | ```$ sbt scalafmt test:scalafmt it:scalafmt``` |
| dependencyCheck         | Runs dependency-check against the current project. It aggregates dependencies and generates a report | ```$ sbt dependencyCheck```                    |
| dependencyUpdates       | Shows a list of project dependencies that can be updated                                             | ```$ sbt dependencyUpdates```                  |
| dependencyUpdatesReport | Writes a list of project dependencies to a file                                                      | ```$ sbt dependencyUpdatesReport```            |

### CTC Traders API related documentation

- [CTC Traders API specifications](https://developer.service.hmrc.gov.uk/api-documentation/docs/api/service/common-transit-convention-traders/2.0) lets you see a list of our endpoints.

### Helpful information

You can find helpful guides on the [HMRC Developer Hub](https://developer.service.hmrc.gov.uk/api-documentation/docs/using-the-hub).

### Reporting Issues

You can create a [GitHub issue](https://github.com/hmrc/common-transit-convention-traders/issues). Alternatively you can contact our Software Development Support team. Email them at sdsteam@hmr.gov.uk, to receive a form where you can add details about your requirements and questions.


### License

This code is open source software licensed under the [Apache 2.0 ```License]("http://www.apache.org/licenses/LICENSE-2.0.html").