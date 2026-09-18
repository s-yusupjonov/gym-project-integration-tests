# integration-tests

Cucumber integration tests for the **real, non-mocked** integration between `gym-crm` and
`trainer-workload-service`: gym-crm publishes a JMS message to the ActiveMQ queue
`workload.events.queue` whenever a training is added or cancelled (`WorkloadClientImpl`), and
trainer-workload-service consumes it (`WorkloadEventListener`) and updates the workload it serves
over its own REST API. **gym-crm never calls trainer-workload-service's REST API directly** - this
was re-confirmed while building this module (no `RestTemplate`/`WebClient`/Feign client anywhere in
gym-crm's source, and no reference to trainer-workload-service's host/port outside a log message).

## Module layout

```
├── discovery-service/
├── gym-crm/                        (pom.xml: spring-boot-maven-plugin now uses classifier="exec")
├── trainer-workload-service/       (pom.xml: spring-boot-maven-plugin now uses classifier="exec")
└── integration-tests/              (this module - standalone sibling, NOT a reactor child)
    ├── pom.xml
    └── src/test/
        ├── java/com/gym/integration/cucumber/
        │   ├── IntegrationTestEnvironment.java   boots both real apps + Testcontainers, once per suite
        │   ├── InternalJwtSupport.java            mints the internal "caller=gym-crm" JWT
        │   ├── GymCrmApiClient.java               thin REST client for gym-crm's public API
        │   ├── WorkloadApiClient.java              thin REST client for trainer-workload-service's API
        │   ├── ScenarioContext.java               per-scenario shared state (picocontainer DI)
        │   ├── Hooks.java                          @BeforeAll / @AfterAll suite lifecycle
        │   ├── RunCucumberIT.java                  JUnit Platform Suite entry point (failsafe)
        │   └── steps/
        │       ├── RegistrationSteps.java
        │       └── TrainingWorkloadSteps.java
        └── resources/features/
            └── cross_service_workload.feature
```

This module is a **standalone sibling** of `gym-crm` and `trainer-workload-service`, not a reactor
child of either, and there's no root aggregator pom (there wasn't one in this repo before, and
introducing one would have meant restructuring both existing modules' `<parent>` blocks - each
currently parents directly off `spring-boot-starter-parent`). A plain sibling module with a
documented build order is the smaller, less invasive change.

## Build order (required)

`integration-tests` depends on the other two modules' **plain jars** as regular Maven
dependencies, so they must be installed to the local repository first:

```bash
mvn -f gym-crm/pom.xml install -DskipTests
mvn -f trainer-workload-service/pom.xml install -DskipTests
mvn -f integration-tests/pom.xml verify
```

### Why `gym-crm/pom.xml` and `trainer-workload-service/pom.xml` needed a one-line change

Both poms' `spring-boot-maven-plugin` previously repackaged their jar **in place**: `mvn install`
left behind only the executable Spring Boot fat jar, whose application classes live nested under
`BOOT-INF/classes/` inside the jar. That's fine for `java -jar app.jar`, but it is *not* usable as
a normal Maven `<dependency>` - a consuming module's classpath doesn't unpack `BOOT-INF/`, so
`com.gym.crm.Application` etc. would not resolve.

The fix (standard Spring Boot practice for "use my Boot app as a library"): give the
`spring-boot-maven-plugin` a `<classifier>exec</classifier>`. This makes `repackage` produce a
separate `gym-crm-1.0.0-exec.jar` / `trainer-workload-service-0.0.1-SNAPSHOT-exec.jar` (the
runnable one), while `mvn install` goes back to installing the **plain** jar as the main
artifact - which is what a `<dependency>` needs. Since the runnable jar's filename changed, both
`gym-crm/Dockerfile` and `trainer-workload-service/Dockerfile` were updated to `COPY` the
`-exec.jar` file instead. `docker-compose.full-stack.yml` needed no change (it only references the
`Dockerfile`s, not jar names directly).

## What the harness does

`IntegrationTestEnvironment` boots the actual `com.gym.crm.Application` and
`com.gym.workload.TrainerWorkloadServiceApplication` classes - the same ones used in production -
in this one JVM via `SpringApplicationBuilder`, each on a random port (`server.port=0`), with
Eureka disabled on both, wired to:

- a single Testcontainers ActiveMQ container (`apache/activemq-classic:6.1.8`, matching the image
  already used in `docker-compose.full-stack.yml`), shared by both apps;
- gym-crm's own H2 in-memory database (`create-drop`, no container needed);
- a Testcontainers MongoDB container (`mongo:7.0`) for trainer-workload-service.

Both apps and both containers are started once, lazily, by a Cucumber `@BeforeAll` hook
(`Hooks`), and shared by every scenario - starting two Spring contexts and two containers per
scenario would be prohibitively slow. Because state is shared for the whole suite, every scenario
registers a **freshly, uniquely named** trainee/trainer (`RegistrationSteps`), so scenarios can't
interfere with each other's workload numbers.

`trainer-workload-service`'s REST API requires a signed internal JWT with subject `gym-crm`
(`internal-auth.allowed-callers`); `InternalJwtSupport` mints one using the same secret the harness
passed to the service at startup - standing in for whatever real internal caller would present one,
since gym-crm itself never calls that API.

## Feature scenarios (`cross_service_workload.feature`)

1. **Add increases workload** - add a 55-minute training, poll trainer-workload-service until it
   reports 55 minutes for that trainer/month.
2. **Cancel decreases workload** - add a 40-minute training, wait for it to appear, cancel it, poll
   until the workload is back to 0.
3. **Rejected add (404, unknown trainee) never reaches trainer-workload-service** - gym-crm's
   `TrainingController` looks up the trainee before calling `TrainingService.addTraining`, so
   `WorkloadClientImpl.notify()` is never invoked and no JMS message is ever published.
4. **Rejected add (400, non-positive duration) never reaches trainer-workload-service** - bean
   validation on `AddTrainingRequest` rejects the request before the controller method body runs
   at all.

All polling uses Awaitility (`atMost`/`pollInterval`/`untilAsserted`) rather than a fixed sleep,
since the JMS → listener → Mongo path is asynchronous. The two "never reaches" scenarios still wait
out a bounded observation window before asserting absence, since a fixed sleep is inherently the
right tool for proving a negative within a time bound - Awaitility only helps prove a positive.

**Scenario deliberately not included:** an ActiveMQ-broker-outage case. `TrainingService.
notifyWorkloadService` swallows every exception `WorkloadClientImpl.notify()` can throw (see
`gym-crm/src/main/java/com/gym/crm/service/TrainingService.java`), so a broker-outage scenario
would only be observable as "gym-crm's HTTP call still returns 200" - it says nothing about
trainer-workload-service, which is the integration this module is about. That specific behaviour
(gym-crm's own resilience to a JMS failure) is more naturally a gym-crm-only test with a mocked
`JmsTemplate`, not something that needs two real services and two containers.

## Verification run

**This could not be executed in the sandbox this module was written in**: that environment has no
`mvn` installed and no network access to Maven Central (only a small allowlist of `npm`/`pypi`/
`crates`/GitHub-adjacent domains, which doesn't include `repo.maven.apache.org` or
`repo1.maven.org`), so dependency resolution for a from-scratch Maven build isn't possible there.
Docker is also not available in that sandbox, which Testcontainers needs regardless.

Every class here was written against the project's own existing conventions (gym-crm's and
trainer-workload-service's own Cucumber harnesses, DTOs, controllers, and security config, all
read directly from the uploaded source) and against the real, version-matched (Testcontainers
`1.20.1`) Testcontainers ActiveMQ/MongoDB APIs (verified via their published docs/Javadoc rather
than assumed), but **it has not been compiled or run**. Please run the build-order commands above
locally (Docker required) and treat the first run as a normal first-run shakedown - the most likely
rough edges, if any, are minor property-name or classpath-scope mismatches rather than anything
structural, since the design was cross-checked line-by-line against the real controllers, security
filters, and event listener code on both sides.
# gym-project-integration-tests
