# WireMock OpenAPI Generator

A production-ready Java application that reads an **OpenAPI 3.0** specification and automatically
generates **WireMock** stub mappings — one file per example per endpoint — so that every named
example in the spec can be served as a distinct mock response.

## Key Features

- 📄 **OpenAPI 3.0 parsing** — YAML and JSON formats supported  
- 🔀 **Multiple stubs per endpoint** — one stub file per named `examples` entry  
- 🎭 **WireMock scenario system** — switch between stubs at runtime without restart  
- 🔧 **Runtime overrides** — Java client and curl commands for Admin API mutations  
- 🐳 **Docker Compose deployment** — single-command start  
- 📦 **Embedded server mode** — no Docker required for local development  

---

## Architecture

```
┌───────────────────┐        ┌────────────────────────────┐
│  sample-openapi   │──────▶ │  OpenApiToWireMockGenerator │
│     .yaml         │        │  (swagger-parser)           │
└───────────────────┘        └────────────┬───────────────┘
                                          │  writes JSON files
                                          ▼
                             ┌────────────────────────────┐
                             │  wiremock/mappings/*.json   │
                             └────────────┬───────────────┘
                                          │  volume mount
                          ┌───────────────┴──────────────┐
                          │       WireMock Server         │
                          │  (Docker or Embedded JVM)     │
                          └───────────────┬──────────────┘
                                          │  Admin API
                          ┌───────────────▼──────────────┐
                          │  EnhancedRuntimeStubManager   │
                          │  (HTTP client → /__admin)     │
                          └──────────────────────────────┘
```

---

## Quick Start

```bash
# 1. Build and generate stubs
./generate-stubs.sh

# 2. Start WireMock
./start-wiremock.sh

# 3. Test the API
curl http://localhost:8080/api/users
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

`OpenApiToWireMockGenerator` uses [swagger-parser](https://github.com/swagger-api/swagger-parser)
to load the spec with all `$ref` pointers fully resolved.  It then iterates every path and every
HTTP method (`GET`, `POST`, `PUT`, `DELETE`, `PATCH`).

### Multiple Examples → Multiple Stubs

For each response that contains a `content.application/json.examples` map, **one stub file is
created per named example**:

```yaml
# OpenAPI spec
/api/users/{id}:
  get:
    responses:
      '200':
        content:
          application/json:
            examples:
              normalUser:   # → getUser-normalUser.json
                value: { ... }
              adminUser:    # → getUser-adminUser.json
                value: { ... }
              inactiveUser: # → getUser-inactiveUser.json
                value: { ... }
```

### Scenario System

Each group of stubs for the same `operationId` shares a **WireMock scenario**.  The active
example is selected by the scenario's current state.

```
scenarioName        = operationId        (e.g. "getUser")
requiredScenarioState = exampleName     (e.g. "normalUser")
```

Switch the active stub at any time:

```bash
curl -X PUT http://localhost:8080/__admin/scenarios/getUser/state \
     -H "Content-Type: application/json" \
     -d '{"state": "adminUser"}'
```

### Priority System

| Stub type | Priority value | Effect |
|-----------|---------------|--------|
| Generated (default) | 5 | Baseline mock |
| Runtime override | 1 | Always wins over generated stubs |

### Path Parameters → Regex

`/api/users/{id}` is translated to the WireMock URL pattern `/api/users/([^/]+)` so any value
is matched.

### Array Responses

Array-typed examples are stored as `jsonBody` arrays and can be manipulated element-by-element
via `EnhancedRuntimeStubManager` without recreating the entire stub.

---

## Sample OpenAPI File

`src/main/resources/sample-openapi.yaml` defines:

| Endpoint | Method | Examples |
|----------|--------|----------|
| `/api/users` | GET | `smallList`, `largeList`, `emptyList` |
| `/api/users` | POST | `regularUser`, `adminUser` |
| `/api/users/{id}` | GET | `normalUser`, `adminUser`, `inactiveUser` |
| `/api/users/{id}` | PUT | `updated` |
| `/api/users/{id}` | DELETE | *(204 No Content)* |

Running `./generate-stubs.sh` produces **10 stub files** in `wiremock/mappings/`.

---

## Generated Stub Example

`wiremock/mappings/getUser-normalUser.json`

```json
{
  "request": {
    "method": "GET",
    "urlPathPattern": "/api/users/([^/]+)"
  },
  "response": {
    "status": 200,
    "jsonBody": {
      "id": 1,
      "name": "Alice Johnson",
      "email": "alice@example.com",
      "role": "user",
      "status": "active",
      "createdAt": "2023-01-15T08:00:00Z",
      "profile": {
        "bio": "Software engineer",
        "location": "New York, USA",
        "website": "https://alice.example.com"
      },
      "permissions": ["read", "write"]
    },
    "headers": { "Content-Type": "application/json" }
  },
  "scenarioName": "getUser",
  "requiredScenarioState": "normalUser",
  "priority": 5,
  "metadata": {
    "generatedFrom": "OpenAPI",
    "operationId": "getUser",
    "exampleName": "normalUser",
    "generatedAt": 1718000000000
  }
}
```

---

## Docker Integration

`docker-compose.yml` mounts the generated stub files read-only into the WireMock container:

```
./wiremock/mappings  →  /home/wiremock/mappings
./wiremock/__files   →  /home/wiremock/__files
```

### Starting WireMock (Docker — preferred)

```bash
./start-wiremock.sh
```

The script:
1. Checks Docker is running
2. Generates stubs if the mappings directory is empty
3. Stops any existing container
4. Starts `docker-compose up -d`
5. Polls the health check and reports when ready

### Starting WireMock (Embedded — no Docker)

```bash
java -cp target/wiremock-openapi-generator-1.0.0-jar-with-dependencies.jar \
     com.example.mockgen.WireMockServerRunner \
     wiremock/mappings 8080
```

Or run the full end-to-end workflow (generates + starts + demonstrates overrides):

```bash
java -jar target/wiremock-openapi-generator-1.0.0-jar-with-dependencies.jar
```

---

## Runtime Override Examples

All examples below target `http://localhost:8080`.

### Override entire response (curl)

```bash
curl -X POST http://localhost:8080/__admin/mappings \
     -H "Content-Type: application/json" \
     -d '{
       "request": { "method": "GET", "urlPath": "/api/users/999" },
       "response": {
         "status": 200,
         "headers": { "Content-Type": "application/json" },
         "jsonBody": { "id": 999, "name": "Custom User", "role": "admin" }
       },
       "priority": 1
     }'
```

### Override entire response (Java)

```java
EnhancedRuntimeStubManager manager = new EnhancedRuntimeStubManager("localhost", 8080);

Map<String, Object> body = Map.of(
    "id", 999,
    "name", "Custom User",
    "role", "admin"
);
manager.overrideStub("GET", "/api/users/999", body);
```

### Patch a single property

```java
// Set name of the GET /api/users response's first element
manager.patchStubProperty("GET", "/api/users", "0.name", "Patched Name");
```

### Update a nested property

```java
// Update profile.location inside GET /api/users/1
manager.patchStubProperty("GET", "/api/users/([^/]+)", "profile.location", "Berlin, Germany");
```

### Update an array item by index

```java
Map<String, Object> updates = Map.of("email", "new@example.com", "status", "inactive");
manager.patchArrayItem("GET", "/api/users", 0, updates);
```

### Add an item to an array

```java
Map<String, Object> newUser = Map.of("id", 100, "name", "New User", "email", "new@example.com");
manager.addArrayItem("GET", "/api/users", newUser);
```

### Remove an item from an array

```java
// Remove the element at index 1
manager.removeArrayItem("GET", "/api/users", 1);
```

### Switch scenario state

```bash
# Serve the "adminUser" example for GET /api/users/{id}
curl -X PUT http://localhost:8080/__admin/scenarios/getUser/state \
     -H "Content-Type: application/json" \
     -d '{"state": "adminUser"}'
```

```java
manager.switchScenarioState("getUser", "adminUser");
```

### Reset to original state

```bash
curl -X POST http://localhost:8080/__admin/mappings/reset
```

```java
manager.resetToOriginal();
```

---

## Testing

Step-by-step:

```bash
# 1. Build
mvn clean package

# 2. Generate stubs
./generate-stubs.sh

# 3. Verify stub files
ls wiremock/mappings/

# 4. Start WireMock
./start-wiremock.sh

# 5. Test endpoints
curl http://localhost:8080/api/users
curl http://localhost:8080/api/users/1
curl -X POST http://localhost:8080/api/users \
     -H "Content-Type: application/json" \
     -d '{"name":"Test","email":"t@example.com"}'

# 6. Runtime override
curl -X POST http://localhost:8080/__admin/mappings \
     -H "Content-Type: application/json" \
     -d '{"request":{"method":"GET","urlPath":"/api/users/999"},"response":{"status":200,"jsonBody":{"id":999,"name":"Override"}},"priority":1}'

# 7. Verify override
curl http://localhost:8080/api/users/999

# 8. View all registered stubs
curl http://localhost:8080/__admin/mappings

# 9. Stop server
./stop-wiremock.sh
```

Or run the automated test suite:

```bash
./test-stubs.sh
```

---

## Project Structure

```
wiremock-openapi-generator/
├── pom.xml                                    # Maven build descriptor
├── docker-compose.yml                         # WireMock Docker service
├── .dockerignore
├── .gitignore
│
├── generate-stubs.sh                          # Build + generate stubs
├── start-wiremock.sh                          # Start Docker WireMock
├── test-stubs.sh                              # Smoke-test endpoints
├── stop-wiremock.sh                           # Stop Docker WireMock
│
├── src/main/
│   ├── java/com/example/mockgen/
│   │   ├── OpenApiToWireMockGenerator.java    # Parses OpenAPI, writes stub files
│   │   ├── WireMockServerRunner.java          # Manages embedded WireMock lifecycle
│   │   ├── EnhancedRuntimeStubManager.java    # Admin API client (CRUD on stubs)
│   │   └── MockServerWorkflow.java            # End-to-end demo; main() entry point
│   └── resources/
│       └── sample-openapi.yaml                # Sample OpenAPI 3.0 specification
│
└── wiremock/
    ├── mappings/                              # Generated stub JSON files land here
    │   └── .gitkeep
    └── __files/                               # Static files for body references
        └── .gitkeep
```

---

## Troubleshooting

### Port 8080 is already in use

```bash
# Find and kill the process using port 8080
lsof -ti:8080 | xargs kill -9
# Or change the port in docker-compose.yml and start-wiremock.sh
```

### Stubs not loading

- Ensure `./generate-stubs.sh` completed without errors
- Check `wiremock/mappings/` contains `*.json` files
- Inspect container logs: `docker-compose logs wiremock`

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

Verify the override stub has `"priority": 1`.  Generated stubs have `"priority": 5`.
Lower numbers take precedence in WireMock.

```bash
# List all stubs and their priorities
curl http://localhost:8080/__admin/mappings | python3 -m json.tool
```

---

## Advanced Usage

### Use your own OpenAPI file

```bash
java -cp target/wiremock-openapi-generator-1.0.0-jar-with-dependencies.jar \
     com.example.mockgen.OpenApiToWireMockGenerator \
     /path/to/your-api.yaml \
     wiremock/mappings
```

### CI/CD integration

```yaml
# Example GitHub Actions step
- name: Generate WireMock stubs
  run: |
    mvn clean package -q
    java -cp target/wiremock-openapi-generator-1.0.0-jar-with-dependencies.jar \
         com.example.mockgen.OpenApiToWireMockGenerator \
         src/main/resources/sample-openapi.yaml \
         wiremock/mappings

- name: Start WireMock
  run: docker-compose up -d

- name: Wait for WireMock
  run: |
    for i in $(seq 1 30); do
      curl -sf http://localhost:8080/__admin/health && break
      sleep 2
    done
```

### Multiple environments

Add environment-specific volumes or docker-compose overrides:

```yaml
# docker-compose.override.yml
services:
  wiremock:
    volumes:
      - ./wiremock/mappings-prod:/home/wiremock/mappings:ro
```

---

## API Reference

| URL | Description |
|-----|-------------|
| `http://localhost:8080/api/users` | List users |
| `http://localhost:8080/api/users/{id}` | Get / update / delete user |
| `http://localhost:8080/__admin/mappings` | List all stub mappings |
| `http://localhost:8080/__admin/mappings` (POST) | Add a new stub |
| `http://localhost:8080/__admin/mappings/reset` (POST) | Reset to disk-persisted stubs |
| `http://localhost:8080/__admin/scenarios` | List all scenarios |
| `http://localhost:8080/__admin/scenarios/{name}/state` (PUT) | Switch scenario state |
| `http://localhost:8080/__admin/health` | Health check |

---

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes (`git commit -m "Add my feature"`)
4. Push to the branch (`git push origin feature/my-feature`)
5. Open a Pull Request

## License

This project is released under the [MIT License](LICENSE).
