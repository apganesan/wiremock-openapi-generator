# WireMock OpenAPI Generator

A Java library that reads an **OpenAPI 3.0** specification and automatically generates
**WireMock** stub mappings, then provides a clean API for switching between examples and
patching response fields at test time — without restarting the server.

## Key Features

- 📄 **OpenAPI 3.0 parsing** — YAML and JSON formats supported
- 📁 **One stub per endpoint** — first example served by default; switch to any named example at runtime without restarting
- 🔧 **Fluent test API** — select an example and patch individual fields in a single call
- ✅ **Schema validation** — examples are validated against the OpenAPI response schema at generation time
- 🐳 **Docker Compose deployment** — single-command start; recommended for integration tests
- 📦 **Embedded server mode** — random-port, in-process WireMock for lightweight unit tests
- 🔌 **Extensible interfaces** — swap out `StubGenerator` or `MockServer` implementations

---

## Architecture

```
┌──────────────────────┐        ┌──────────────────────────────┐
│   openapi.yaml       │──────▶ │  OpenApiStubGenerator        │
│  (YAML / JSON spec)  │        │  (swagger-parser)            │
└──────────────────────┘        └─────────────┬────────────────┘
                                              │  writes JSON files
                                              ▼
                                 ┌────────────────────────────┐
                                 │  wiremock/mappings/*.json  │
                                 │  (one file per endpoint)   │
                                 └─────────────┬──────────────┘
                                               │  loaded on start
                          ┌────────────────────┴─────────────┐
                          │      EmbeddedWireMockServer       │
                          │   (or Docker via docker-compose)  │
                          └────────────────────┬─────────────┘
                                               │  Admin API
                          ┌────────────────────▼─────────────┐
                          │       WireMockAdminClient         │
                          │  selectExample / patch / reset    │
                          └──────────────────────────────────┘
                                               ▲
                          ┌────────────────────┴─────────────┐
                          │    WireMockIntegrationSupport     │
                          │  (main facade — builder pattern)  │
                          └──────────────────────────────────┘
```

---

## Quick Start

```bash
# 1. Build and generate stubs
./generate-stubs.sh

# 2. Start WireMock
./start-wiremock.sh

# 3. Test the API
curl http://localhost:8080/med/1
```

---

## Prerequisites

| Tool | Minimum version |
|------|----------------|
| Java | 11 |
| Maven | 3.6 |
| Docker | 20.x (for Docker mode) |
| curl | any |

---

## Installation

```bash
git clone https://github.com/apganesan/wiremock-openapi-generator.git
cd wiremock-openapi-generator

# Build (downloads dependencies, creates jar-with-dependencies)
mvn clean package
```

---

## How Stub Generation Works

### OpenAPI Parsing

`OpenApiStubGenerator` uses [swagger-parser](https://github.com/swagger-api/swagger-parser)
to load the spec with all `$ref` pointers fully resolved. It iterates every path and every
HTTP method (`GET`, `POST`, `PUT`, `DELETE`, `PATCH`).

### One Stub per Endpoint

**One stub file is written per endpoint** (not per example). When multiple named examples
exist in the spec:

- The **first example** becomes the default `jsonBody` served for any matching request.
- **All examples** (name + body) are stored in `metadata.examples` so a test can call
  `selectExample(index)` to switch the active response at runtime — no WireMock scenario
  state machine involved.

```yaml
# OpenAPI spec
/med/{id}:
  get:
    responses:
      '200':
        content:
          application/json:
            examples:
              ibuprofen:    # ← default response (index 0)
                value: { id: 1, name: Ibuprofen, ... }
              amoxicillin:  # ← index 1
                value: { id: 2, name: Amoxicillin, ... }
              metformin:    # ← index 2
                value: { id: 3, name: Metformin, ... }
```

Running `./generate-stubs.sh` produces **one file** (`getMedicationById.json`) that
contains all three examples in its metadata.

### Schema Validation

Each example is validated against the OpenAPI response schema before the stub is written.
Violations are logged as `WARN` — generation always completes so a partial spec does not
block the build.

### Priority System

| Stub type | Priority | Effect |
|-----------|----------|--------|
| Generated (default) | 5 | Baseline mock |
| Runtime override | 1 | Always wins over generated stubs |

### Path Parameters → Regex

`/med/{id}` is translated to the WireMock URL pattern `/med/([^/]+)` so any value matches.

---

## Sample OpenAPI File

`src/main/resources/openapi.yaml` defines a **Med Target System API**:

| Endpoint | Method | Examples |
|----------|--------|----------|
| `/med` | POST | `ibuprofen`, `amoxicillin`, `metformin` |
| `/med/{id}` | GET | `ibuprofen`, `amoxicillin`, `metformin` |

Running `./generate-stubs.sh` produces **2 stub files** in `wiremock/mappings/`.

---

## Generated Stub Example

`wiremock/mappings/getMedicationById.json`

```json
{
  "request": {
    "method": "GET",
    "urlPathPattern": "/med/([^/]+)"
  },
  "response": {
    "status": 200,
    "jsonBody": {
      "id": 1,
      "name": "Ibuprofen",
      "type": "analgesic",
      "properties": {
        "dosage": "200mg",
        "route": "oral",
        "frequency": "twice daily"
      }
    },
    "headers": { "Content-Type": "application/json" }
  },
  "priority": 5,
  "metadata": {
    "generatedFrom": "OpenAPI",
    "operationId": "getMedicationById",
    "examples": [
      { "name": "ibuprofen",   "body": { "id": 1, "name": "Ibuprofen", ... } },
      { "name": "amoxicillin", "body": { "id": 2, "name": "Amoxicillin", ... } },
      { "name": "metformin",   "body": { "id": 3, "name": "Metformin", ... } }
    ]
  }
}
```

---

## Using in Tests — `WireMockIntegrationSupport`

`WireMockIntegrationSupport` is the main entry point for JUnit tests. It generates stubs,
starts an embedded WireMock server, and exposes a fluent API for runtime overrides.

### Minimal Setup (JUnit 5)

```java
class MedicationServiceTest {

    static WireMockIntegrationSupport wiremock;

    @BeforeAll
    static void startServer() {
        wiremock = WireMockIntegrationSupport.builder()
                .openApiFile("src/test/resources/openapi.yaml")
                // .port(8080)          // fixed port (default: random free port)
                // .mappingsDir("...")   // explicit dir (default: temp dir, auto-cleaned)
                .build()
                .start();
    }

    @BeforeEach
    void resetToDefault() {
        wiremock.resetStubs();   // back to first OpenAPI example before each test
    }

    @AfterAll
    static void stopServer() {
        wiremock.stop();
    }
}
```

### Selecting an Example

```java
// Use the second example (index 1 = amoxicillin)
wiremock.forStub("GET", "/med/([^/]+)").example(1).apply();
```

### Patching Fields on the Active Stub

```java
// Change a single field; all other fields keep their current values
wiremock.forStub("GET", "/med/([^/]+)")
        .with("status", "discontinued")
        .apply();
```

### Select an Example AND Patch Fields

```java
// Switch to metformin (index 2) then override just the status field
wiremock.forStub("GET", "/med/([^/]+)")
        .example(2)
        .with("status", "recalled")
        .apply();
```

### Reset to Default

```java
wiremock.resetStubs();   // removes all runtime overrides; first example is served again
```

### Array Body Operations (via `StubClient`)

```java
StubClient client = wiremock.getStubClient();

// Patch one item in an array response
client.patchArrayItem("GET", "/med", 0, Map.of("name", "Updated Drug"));

// Append an item
client.addArrayItem("GET", "/med", Map.of("id", 99, "name", "New Drug"));

// Remove item at index 1
client.removeArrayItem("GET", "/med", 1);
```

---

## Docker Integration

`docker-compose.yml` mounts the generated stub files read-only into the WireMock container:

```
./wiremock/mappings  →  /home/wiremock/mappings
./wiremock/__files   →  /home/wiremock/__files
```

### Starting WireMock (Docker — recommended for integration tests)

```bash
./start-wiremock.sh
```

The script:
1. Checks Docker is running
2. Generates stubs if the mappings directory is empty
3. Stops any existing container
4. Runs `docker compose up -d`
5. Polls the health check and reports when ready

### Starting WireMock (Embedded — lightweight unit tests only)

For isolated, fast unit tests where a full Docker stack isn't appropriate, `WireMockIntegrationSupport`
starts an in-process WireMock server automatically (see _Using in Tests_ above).
For integration tests that need to verify real network behaviour or interact with other
Docker-based services, prefer the Docker mode above.

```bash
java -cp target/wiremock-openapi-generator-1.0.0-jar-with-dependencies.jar \
     com.agfa.orbis.common.mockengine.StubGeneratorCli \
     src/main/resources/openapi.yaml \
     wiremock/mappings
```

---

## Running Steps

### Step 1 — Build

```bash
mvn clean package
```

Produces `target/wiremock-openapi-generator-1.0.0-jar-with-dependencies.jar`.

### Step 2 — Generate Stubs

```bash
./generate-stubs.sh
```

Reads `src/main/resources/openapi.yaml` and writes JSON stubs to `wiremock/mappings/`.

Or run the generator directly:

```bash
java -cp target/wiremock-openapi-generator-1.0.0-jar-with-dependencies.jar \
     com.agfa.orbis.common.mockengine.StubGeneratorCli \
     src/main/resources/openapi.yaml \
     wiremock/mappings
```

### Step 3 — Start WireMock

**Docker (recommended for integration tests):**

```bash
./start-wiremock.sh
```

**Embedded (for lightweight unit tests only):**

Use `WireMockIntegrationSupport` in your test class — it generates stubs and starts an
in-process server automatically (see _Using in Tests_ above). This avoids Docker overhead
for fast, isolated unit tests, but is not a substitute for Docker-based integration testing.

### Step 4 — Verify Endpoints

```bash
# Default (first example — ibuprofen)
curl http://localhost:8080/med/1

# Create a medication
curl -X POST http://localhost:8080/med \
     -H "Content-Type: application/json" \
     -d '{"id":1,"name":"Ibuprofen","type":"analgesic"}'

# Admin: list all registered stubs
curl http://localhost:8080/__admin/mappings

# Admin: reset stubs to disk state
curl -X POST http://localhost:8080/__admin/mappings/reset
```

### Step 5 — Runtime Override (curl)

```bash
curl -X POST http://localhost:8080/__admin/mappings \
     -H "Content-Type: application/json" \
     -d '{
       "request": { "method": "GET", "urlPath": "/med/999" },
       "response": {
         "status": 200,
         "headers": { "Content-Type": "application/json" },
         "jsonBody": { "id": 999, "name": "Override Drug", "type": "experimental" }
       },
       "priority": 1
     }'

# Verify
curl http://localhost:8080/med/999
```

### Step 6 — Run Tests

```bash
# JUnit tests (requires no running server — embedded WireMock starts automatically)
mvn test

# Smoke-test a running Docker server
./test-stubs.sh
```

### Step 7 — Stop WireMock (Docker)

```bash
./stop-wiremock.sh
```

---

## Project Structure

```
wiremock-openapi-generator/
├── pom.xml                                        # Maven build descriptor
├── docker-compose.yml                             # WireMock Docker service
├── generate-stubs.sh                              # Build + generate stubs
├── start-wiremock.sh                              # Start Docker WireMock
├── test-stubs.sh                                  # Smoke-test endpoints
├── stop-wiremock.sh                               # Stop Docker WireMock
│
├── src/
│   ├── main/
│   │   ├── java/com/agfa/orbis/common/mockengine/
│   │   │   ├── api/
│   │   │   │   ├── StubGenerator.java             # Interface: spec → stub files
│   │   │   │   ├── MockServer.java                # Interface: start/stop server
│   │   │   │   └── StubClient.java                # Interface: Admin API operations
│   │   │   ├── impl/
│   │   │   │   ├── OpenApiStubGenerator.java      # Parses OpenAPI, writes stub JSON
│   │   │   │   ├── EmbeddedWireMockServer.java    # Embedded WireMock lifecycle
│   │   │   │   ├── WireMockAdminClient.java       # Admin API client (StubClient impl)
│   │   │   │   └── OpenApiExampleValidator.java   # Schema validation for examples
│   │   │   ├── WireMockIntegrationSupport.java    # Main facade (builder pattern)
│   │   │   ├── StubOverride.java                  # Fluent override DSL
│   │   │   └── StubGeneratorCli.java              # CLI entry point
│   │   └── resources/
│   │       └── openapi.yaml                       # Sample OpenAPI 3.0 specification
│   └── test/
│       ├── java/com/agfa/orbis/common/mockengine/
│       │   └── MedicationServiceTest.java         # JUnit 5 integration tests
│       └── resources/
│           └── openapi.yaml                       # OpenAPI spec used by tests
│
└── wiremock/
    ├── mappings/                                  # Generated stub JSON files
    └── __files/                                   # Static body reference files
```

---

## Troubleshooting

### Port 8080 is already in use

```bash
# Find what's using port 8080
lsof -i:8080
# Change the port in docker-compose.yml if needed
```

### Stubs not loading

- Ensure `./generate-stubs.sh` completed without errors
- Check `wiremock/mappings/` contains `*.json` files
- Inspect container logs: `docker compose logs wiremock`

### Docker issues

```bash
# Pull the image manually
docker pull wiremock/wiremock:3.3.1

# Check container status
docker ps -a | grep wiremock

# Restart
./stop-wiremock.sh && ./start-wiremock.sh
```

### Override not taking effect

Verify the override stub has `"priority": 1`. Generated stubs use `"priority": 5`.
Lower numbers take precedence in WireMock.

```bash
curl http://localhost:8080/__admin/mappings | python3 -m json.tool
```

---

## Advanced Usage

### Use your own OpenAPI file

```bash
java -cp target/wiremock-openapi-generator-1.0.0-jar-with-dependencies.jar \
     com.agfa.orbis.common.mockengine.StubGeneratorCli \
     /path/to/your-api.yaml \
     wiremock/mappings
```

Or point `WireMockIntegrationSupport` at it in tests:

```java
WireMockIntegrationSupport.builder()
        .openApiFile("/path/to/your-api.yaml")
        .build()
        .start();
```

### Custom `StubGenerator` or `MockServer`

```java
WireMockIntegrationSupport.builder()
        .openApiFile("my-api.yaml")
        .stubGenerator(new MyCustomStubGenerator())   // implements StubGenerator
        .mockServer(new MyCustomMockServer())          // implements MockServer
        .build()
        .start();
```

### CI/CD integration

```yaml
# Example GitHub Actions step
- name: Generate WireMock stubs
  run: |
    mvn clean package -q
    java -cp target/wiremock-openapi-generator-1.0.0-jar-with-dependencies.jar \
         com.agfa.orbis.common.mockengine.StubGeneratorCli \
         src/main/resources/openapi.yaml \
         wiremock/mappings

- name: Start WireMock
  run: docker compose up -d

- name: Wait for WireMock
  run: |
    for i in $(seq 1 30); do
      curl -sf http://localhost:8080/__admin/health && break
      sleep 2
    done

- name: Run tests
  run: mvn test
```

---

## API Reference

| URL | Method | Description |
|-----|--------|-------------|
| `http://localhost:8080/med` | POST | Create a medication record |
| `http://localhost:8080/med/{id}` | GET | Get a medication record |
| `http://localhost:8080/__admin/mappings` | GET | List all stub mappings |
| `http://localhost:8080/__admin/mappings` | POST | Add a new stub |
| `http://localhost:8080/__admin/mappings/reset` | POST | Reset to disk-persisted stubs |
| `http://localhost:8080/__admin/health` | GET | Health check |

---

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes (`git commit -m "Add my feature"`)
4. Push to the branch (`git push origin feature/my-feature`)
5. Open a Pull Request

## License

This project is released under the [MIT License](LICENSE).
