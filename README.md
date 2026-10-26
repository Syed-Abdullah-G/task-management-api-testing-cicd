# Task Management API

A Spring Boot REST API for managing tasks, built to demonstrate **backend development, unit testing (JUnit 5 + Mockito), REST API testing (MockMvc), static analysis (SonarQube) and CI/CD (Jenkins)** with Maven.

## Verification status (read this first)

| Item | Status |
|------|--------|
| Source, tests, `pom.xml`, `Jenkinsfile` written | Done |
| `mvn clean verify` executed | **Not verified.** The environment that generated this project could not reach Maven Central (`repo.maven.apache.org`), so dependencies could not be downloaded and nothing was compiled or run. |
| Test results / pass counts | **None reported.** Run the commands below and record your own results. |
| SonarQube analysis | **Not executed.** Configuration and commands are provided; no analysis results or screenshots exist. |
| Jenkins pipeline | **Not executed.** The `Jenkinsfile` is configuration only. |

Everything in this README describes *intended* behaviour until you run it. After your first successful run, update this table.

## Technology stack

| Area | Choice |
|------|--------|
| Language / runtime | Java 17 (builds fine on newer JDKs such as 21) |
| Framework | Spring Boot 3.5.7 (Web, Data JPA, Validation) |
| Database | H2 in-memory |
| Build | Maven (3.9+ recommended) |
| Unit tests | JUnit 5, Mockito (via `spring-boot-starter-test`) |
| API tests | Spring `MockMvc`, `@WebMvcTest`, `@SpringBootTest` |
| Static analysis | SonarQube via `sonar-maven-plugin` 5.1.0.4751 |
| CI/CD | Jenkins declarative pipeline |

Deliberately **not** included: JaCoCo / coverage collection, PowerMock, and any other extra tooling.

## Architecture

```
HTTP request
   |
controller  -- HTTP only: routing, @Valid, status codes
   |
service     -- business rules, validation, logging of events
   |
repository  -- Spring Data JPA (TaskRepository)
   |
entity      -- Task + TaskStatus  ->  H2

dto         -- TaskRequest, TaskStatusUpdateRequest, TaskResponse, ApiError
exception   -- TaskNotFoundException, InvalidTaskException, GlobalExceptionHandler
```

Key decisions:

* **Constructor injection** everywhere; the service depends only on `TaskRepository`.
* **Entities never leave the service**: it returns `TaskResponse` DTOs.
* **One place for errors**: `GlobalExceptionHandler` (`@RestControllerAdvice` + `@ExceptionHandler`) converts exceptions into a consistent JSON body. Lower layers only throw, so each failure is logged once. Stack traces are logged server-side but never returned.
* **Validation in two layers**: Bean Validation on the request DTO (HTTP 400 with per-field messages) and defensive checks in the service (so the service is safe when called from anywhere and is unit-testable).
* **Timestamps** `createdAt` / `updatedAt` are set by JPA lifecycle callbacks (`@PrePersist`, `@PreUpdate`).

## REST API

Base path: `/api/tasks`

| Method | Path | Description | Success | Errors |
|--------|------|-------------|---------|--------|
| POST | `/api/tasks` | Create a task | 201 + `Location` header | 400 |
| GET | `/api/tasks` | List all tasks | 200 | |
| GET | `/api/tasks/{id}` | Get one task | 200 | 404, 400 (non-numeric id) |
| PUT | `/api/tasks/{id}` | Update title, description and optionally status | 200 | 400, 404 |
| PATCH | `/api/tasks/{id}/status` | Change status only | 200 | 400, 404 |
| DELETE | `/api/tasks/{id}` | Delete a task | 204 | 404 |

Rules: `title` is required, not blank, max 100 characters. `description` is optional, max 1000 characters. `status` is `TODO`, `IN_PROGRESS` or `COMPLETED`; on create it defaults to `TODO`, on `PUT` an omitted status keeps the current one.

### Examples

```bash
# Create
curl -i -X POST http://localhost:8080/api/tasks \
  -H "Content-Type: application/json" \
  -d '{"title":"Write unit tests","description":"Cover the service layer"}'

# Read
curl http://localhost:8080/api/tasks
curl http://localhost:8080/api/tasks/1

# Update
curl -X PUT http://localhost:8080/api/tasks/1 \
  -H "Content-Type: application/json" \
  -d '{"title":"Write unit and integration tests","description":"Updated","status":"IN_PROGRESS"}'

# Change status
curl -X PATCH http://localhost:8080/api/tasks/1/status \
  -H "Content-Type: application/json" -d '{"status":"COMPLETED"}'

# Delete
curl -i -X DELETE http://localhost:8080/api/tasks/1
```

Task response shape:

```json
{
  "id": 1,
  "title": "Write unit tests",
  "description": "Cover the service layer",
  "status": "TODO",
  "createdAt": "2026-01-01T10:00:00Z",
  "updatedAt": "2026-01-01T10:00:00Z"
}
```

### Error responses

Every error uses the same structure (`validationErrors` appears only for validation failures):

```json
{
  "timestamp": "2026-01-01T10:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/tasks",
  "validationErrors": { "title": "Title must not be blank" }
}
```

| Situation | Status |
|-----------|--------|
| Validation failure, malformed JSON, unknown enum value, non-numeric id | 400 |
| Task not found | 404 |
| Wrong HTTP method | 405 |
| Database unavailable (`DataAccessException`) | 503 |
| Any other unexpected error | 500 (generic message, no stack trace) |

## Running the application

Requirements: JDK 17+ and Maven 3.9+.

```bash
mvn spring-boot:run
```

The API listens on `http://localhost:8080`. Data lives in an in-memory H2 database and is lost on restart.

## Running the tests

```bash
mvn test            # unit tests + @WebMvcTest slice (Surefire, *Test classes)
mvn clean verify    # everything, including *IT integration tests (Failsafe)
```

Reports are written to `target/surefire-reports/` and `target/failsafe-reports/`.

### Unit tests versus integration tests

| | Unit tests | Integration tests |
|---|-----------|-------------------|
| Classes | `TaskServiceTest`, `TaskControllerWebMvcTest` | `TaskApiIT` |
| Plugin / phase | Surefire, `test` | Failsafe, `integration-test` / `verify` |
| Spring context | None for `TaskServiceTest`; web slice only for `@WebMvcTest` | Full `@SpringBootTest` context |
| Database | None (repository/service mocked) | Real H2 (`application-test.properties`) |
| Purpose | Logic and interactions in isolation, very fast | Layers working together end to end |

`TaskServiceTest` uses `@ExtendWith(MockitoExtension.class)`, `@Mock`, `@InjectMocks`, `@BeforeEach`, `@ParameterizedTest` (`@EnumSource`, `@NullAndEmptySource`, `@ValueSource`), `@DisplayName`, and the Arrange-Act-Assert pattern.

### Test scenarios implemented

**Service (JUnit 5 + Mockito):** create valid task (default status, trimming, explicit status); retrieve existing; missing task; list (non-empty and empty); update details; update keeping status; change status for every enum value; delete existing; invalid input (blank/null/whitespace title, null request, null status); boundary lengths for title and description (at limit accepted, limit+1 rejected); repository failures on `save` (`thenThrow`) and on void `delete` (`doThrow`); and verification that failed or rejected operations never call `save`/`delete` (`never()`, `verifyNoInteractions`).

**Web slice (`@WebMvcTest`, mocked service):** 201 with `Location`; 400 with field errors; malformed JSON; unknown enum; 200 lists and reads; 404 and its error structure; non-numeric id; PUT and PATCH success and failure; 204/404 on delete; 503 and 500 without leaking internals; 405 in the same JSON structure.

**Integration (`@SpringBootTest` + `@AutoConfigureMockMvc` + H2):** create, retrieve, list (including empty), update, change status, delete then 404, 404 on all id-based endpoints, error structure, blank/too-long/at-limit titles, malformed JSON, invalid status (and that nothing changed in the database).

### Mocks, stubs and spies

* **Stub**: returns canned answers so the code under test can run (`when(repo.findById(1L)).thenReturn(Optional.of(task))`). You assert on the *result*.
* **Mock**: a test double that also records how it was used, so you can assert on *interactions* (`verify(repo, times(1)).save(any())`, `verify(repo, never()).delete(any())`). In Mockito a single `@Mock` object acts as both; whether it is "used as a stub" or "used as a mock" depends on what the test asserts.
* **Spy**: wraps a **real** object; real methods run unless you stub them (`spy(new ArrayList<>())`). Use sparingly, for partially real collaborators. This project has no need for a spy because the only collaborator (the repository) is an interface best replaced fully.

### Mockito examples from the tests

```java
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {
    @Mock private TaskRepository taskRepository;
    @InjectMocks private TaskService taskService;

    @Test
    void getTaskById_missingTask_throwsTaskNotFoundException() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());          // stub

        assertThrows(TaskNotFoundException.class, () -> taskService.getTaskById(99L));

        verify(taskRepository, times(1)).findById(99L);                           // interaction
        verify(taskRepository, never()).save(any(Task.class));                    // nothing unintended
    }

    @Test
    void deleteTask_repositoryFailure_propagatesDataAccessException() {
        when(taskRepository.findById(1L)).thenReturn(Optional.of(existingTask));
        doThrow(new DataAccessResourceFailureException("down"))                   // void method
                .when(taskRepository).delete(existingTask);

        assertThrows(DataAccessException.class, () -> taskService.deleteTask(1L));
    }
}
```

`ArgumentCaptor<Task>` is used to inspect exactly what was passed to `save()`.

## SonarQube

### 1. Start a SonarQube server (local example)

```bash
docker run -d --name sonarqube -p 9000:9000 sonarqube:community
```

Check the [official documentation](https://docs.sonarsource.com/sonarqube-server/) for the currently recommended image/tag and installation options. Open `http://localhost:9000` (first login is `admin` / `admin`; you are forced to change the password).

### 2. Create an authentication token

In SonarQube: avatar (top right) > **My Account** > **Security** > **Generate Tokens**. Copy it once; it is not shown again. Store it in an environment variable, never in a file that is committed:

```bash
export SONAR_TOKEN="<your token>"        # Linux/macOS (PowerShell: $env:SONAR_TOKEN="...")
```

### 3. Run the analysis

```bash
mvn clean verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.token=$SONAR_TOKEN
```

Configuration lives in `pom.xml` (`sonar.projectKey=task-management-api`, `sonar.projectName=Task Management API`). Maven's standard `src/main/java` and `src/test/java` are detected automatically, so no `sonar-project.properties` is needed. Surefire and Failsafe XML reports are picked up through `sonar.junit.reportPaths`. No exclusions or suppressions are configured, and **no code coverage (JaCoCo) is configured**, so SonarQube will show no coverage figures.

### 4. Read the results

Open `http://localhost:9000/dashboard?id=task-management-api`. Review: **Reliability** (bugs), **Security** (vulnerabilities) and **Security Hotspots** (code that needs a human review), **Maintainability** (code smells), **Duplications**, and the **Quality Gate** result. Fix real issues in the code rather than suppressing them. No analysis has been run yet, so this README contains no results.

## Jenkins

The `Jenkinsfile` defines these stages: Checkout, Validate Environment, Compile, Unit Tests, Integration Tests, SonarQube Analysis, Quality Gate. The build fails if compilation or any test fails. JUnit results are published after the unit and integration stages (`junit` step), and a `post` block reports success, failure or unstable builds with links to the console and test report.

### Setup

1. **Plugins** (Manage Jenkins > Plugins): Pipeline, Git, JUnit, SonarQube Scanner for Jenkins.
2. **Tools** (Manage Jenkins > Tools): add a JDK named `JDK17` and a Maven installation named `Maven3` (the names must match the `tools` block, or edit the `Jenkinsfile`).
3. **SonarQube token credential**: Manage Jenkins > Credentials > add a **Secret text** credential containing the token from the SonarQube step above.
4. **SonarQube server** (Manage Jenkins > System > SonarQube servers): add a server named `SonarQube`, URL e.g. `http://<sonarqube-host>:9000`, and select the credential from step 3.
5. **Quality gate webhook**: in SonarQube, Administration > Configuration > Webhooks, create `http://<jenkins-host>:8080/sonarqube-webhook/`. Without it, `waitForQualityGate` waits until its timeout. If your setup does not support this, remove the *Quality Gate* stage.
6. **Connect GitHub**: New Item > *Pipeline* (or *Multibranch Pipeline*) > *Pipeline script from SCM* > Git > repository URL, credentials if the repo is private, script path `Jenkinsfile`. Optionally add a GitHub webhook for automatic builds.
7. **Run**: *Build Now*.

### Inspecting results

* **Test results**: build page > *Test Result* (aggregated JUnit data from Surefire and Failsafe).
* **Console / failures**: build page > *Console Output*, or the failed stage in Stage View.
* **Code quality**: Jenkins shows only the pipeline status (and the quality gate verdict). Detailed findings are viewed in SonarQube at `http://<sonarqube-host>:9000/dashboard?id=task-management-api`.

Secrets are never written in the `Jenkinsfile`; the token is injected by `withSonarQubeEnv` and read from shell environment variables.

## Project structure

```
task-management-api/
├── pom.xml
├── Jenkinsfile
├── README.md
├── .gitignore
└── src/
    ├── main/
    │   ├── java/com/example/taskmanager/
    │   │   ├── TaskManagerApplication.java
    │   │   ├── controller/TaskController.java
    │   │   ├── service/TaskService.java
    │   │   ├── repository/TaskRepository.java
    │   │   ├── entity/{Task,TaskStatus}.java
    │   │   ├── dto/{TaskRequest,TaskStatusUpdateRequest,TaskResponse,ApiError}.java
    │   │   └── exception/{TaskNotFoundException,InvalidTaskException,GlobalExceptionHandler}.java
    │   └── resources/application.properties
    └── test/
        ├── java/com/example/taskmanager/
        │   ├── service/TaskServiceTest.java            (JUnit 5 + Mockito)
        │   └── controller/
        │       ├── TaskControllerWebMvcTest.java       (@WebMvcTest)
        │       └── TaskApiIT.java                      (@SpringBootTest + MockMvc + H2)
        └── resources/application-test.properties
```

## Troubleshooting and known limitations

* **`mvn` cannot download dependencies**: check network/proxy access to Maven Central.
* **`release version 17 not supported`**: use JDK 17 or newer.
* **Port 8080 in use**: run with `--server.port=8081`.
* **Quality gate stage hangs**: the SonarQube webhook to Jenkins is missing (see Jenkins setup, step 5).
* **Jenkins stage `Integration Tests`** calls `failsafe:integration-test failsafe:verify` directly and relies on the classes compiled in the earlier stages of the same build.
* **Mockito on newer JDKs** may print a warning about dynamic agent loading; it is harmless.
* **Limitations**: no authentication, no pagination, data is not persisted across restarts (in-memory H2), and no coverage reporting by design.
# task-management-api-testing-cicd
