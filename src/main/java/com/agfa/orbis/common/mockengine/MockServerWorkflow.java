package com.agfa.orbis.common.mockengine;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * End-to-end demonstration workflow:
 * <ol>
 *   <li>Generate WireMock stubs from the bundled sample OpenAPI specification.</li>
 *   <li>Start an embedded WireMock server on port 8080.</li>
 *   <li>Use the {@link EnhancedRuntimeStubManager} to demonstrate runtime overrides.</li>
 *   <li>Print example {@code curl} commands.</li>
 *   <li>Block until the JVM is shut down (Ctrl+C).</li>
 * </ol>
 */
public class MockServerWorkflow {

    private static final Logger log = LoggerFactory.getLogger(MockServerWorkflow.class);

    private static final String OPENAPI_FILE = "src/main/resources/sample-openapi.yaml";
    private static final String MAPPINGS_DIR  = "wiremock/mappings";
    private static final int    PORT          = 8080;
    private static final String HOST          = "localhost";

    public static void main(String[] args) throws Exception {
        MockServerWorkflow workflow = new MockServerWorkflow();
        workflow.run();
    }

    public void run() throws Exception {
        printBanner();

        // ------------------------------------------------------------------
        // Step 1 – Generate stubs from the OpenAPI specification
        // ------------------------------------------------------------------
        log.info("━━━ Step 1: Generating stubs from OpenAPI specification ━━━");
        OpenApiToWireMockGenerator generator = new OpenApiToWireMockGenerator();
        int stubCount = generator.generateStubs(OPENAPI_FILE, MAPPINGS_DIR);
        log.info("Generated {} stub file(s) in '{}'.", stubCount, MAPPINGS_DIR);

        // ------------------------------------------------------------------
        // Step 2 – Start the embedded WireMock server
        // ------------------------------------------------------------------
        log.info("━━━ Step 2: Starting embedded WireMock server ━━━");
        WireMockServerRunner serverRunner = new WireMockServerRunner();
        serverRunner.start(MAPPINGS_DIR, PORT);

        // Give the server a moment to register all mappings
        Thread.sleep(500);

        // ------------------------------------------------------------------
        // Step 3 – Runtime overrides via the Admin API
        // ------------------------------------------------------------------
        log.info("━━━ Step 3: Demonstrating runtime stub overrides ━━━");
        EnhancedRuntimeStubManager manager = new EnhancedRuntimeStubManager(HOST, PORT);

        demonstrateOverrides(manager);

        // ------------------------------------------------------------------
        // Step 4 – Print helpful curl commands
        // ------------------------------------------------------------------
        printCurlCommands();

        // ------------------------------------------------------------------
        // Step 5 – Keep the server running until Ctrl+C
        // ------------------------------------------------------------------
        log.info("━━━ Server is running. Press Ctrl+C to stop. ━━━");
        Thread.currentThread().join();
    }

    // -------------------------------------------------------------------------
    // Runtime override demonstrations
    // -------------------------------------------------------------------------

    private void demonstrateOverrides(EnhancedRuntimeStubManager manager) {
        // 3a – Override a specific GET /api/users/{id} response
        tryOverride(manager);

        // 3b – Patch a single property on the user list
        tryPatchProperty(manager);

        // 3c – Add a new user to the list array
        tryAddArrayItem(manager);

        // 3d – Update one item inside the array
        tryPatchArrayItem(manager);

        // 3e – Remove the last item from the array
        tryRemoveArrayItem(manager);

        // 3f – Switch scenario state for getUser
        trySwitchScenario(manager);
    }

    private void tryOverride(EnhancedRuntimeStubManager manager) {
        try {
            Map<String, Object> customUser = new LinkedHashMap<>();
            customUser.put("id", 999);
            customUser.put("name", "Runtime Override User");
            customUser.put("email", "override@example.com");
            customUser.put("role", "admin");
            customUser.put("status", "active");
            customUser.put("createdAt", "2024-01-01T00:00:00Z");

            manager.overrideStub("GET", "/api/users/([^/]+)", customUser);
        } catch (Exception e) {
            log.warn("Override demonstration skipped: {}", e.getMessage());
        }
    }

    private void tryPatchProperty(EnhancedRuntimeStubManager manager) {
        try {
            manager.patchStubProperty("GET", "/api/users", "0.name", "Patched First User");
        } catch (Exception e) {
            log.warn("Patch property demonstration skipped: {}", e.getMessage());
        }
    }

    private void tryAddArrayItem(EnhancedRuntimeStubManager manager) {
        try {
            Map<String, Object> newUser = new LinkedHashMap<>();
            newUser.put("id", 100);
            newUser.put("name", "Dynamically Added User");
            newUser.put("email", "dynamic@example.com");
            newUser.put("role", "user");
            newUser.put("status", "active");

            manager.addArrayItem("GET", "/api/users", newUser);
        } catch (Exception e) {
            log.warn("Add array item demonstration skipped: {}", e.getMessage());
        }
    }

    private void tryPatchArrayItem(EnhancedRuntimeStubManager manager) {
        try {
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put("email", "updated@example.com");
            updates.put("status", "inactive");

            manager.patchArrayItem("GET", "/api/users", 0, updates);
        } catch (Exception e) {
            log.warn("Patch array item demonstration skipped: {}", e.getMessage());
        }
    }

    private void tryRemoveArrayItem(EnhancedRuntimeStubManager manager) {
        try {
            manager.removeArrayItem("GET", "/api/users", 0);
        } catch (Exception e) {
            log.warn("Remove array item demonstration skipped: {}", e.getMessage());
        }
    }

    private void trySwitchScenario(EnhancedRuntimeStubManager manager) {
        try {
            manager.switchScenarioState("getUser", "adminUser");
        } catch (Exception e) {
            log.warn("Switch scenario demonstration skipped: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Console helpers
    // -------------------------------------------------------------------------

    private void printBanner() {
        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║   WireMock OpenAPI Generator — Demo Workflow         ║");
        log.info("╚══════════════════════════════════════════════════════╝");
    }

    private void printCurlCommands() {
        log.info("━━━ Example curl commands ━━━");
        log.info("  # List users (GET /api/users)");
        log.info("  curl http://localhost:{}/api/users", PORT);
        log.info("");
        log.info("  # Get user by ID (GET /api/users/1)");
        log.info("  curl http://localhost:{}/api/users/1", PORT);
        log.info("");
        log.info("  # Create user (POST /api/users)");
        log.info("  curl -X POST http://localhost:{}/api/users \\", PORT);
        log.info("       -H 'Content-Type: application/json' \\");
        log.info("       -d '{{\"name\":\"Alice\",\"email\":\"alice@example.com\"}}'");
        log.info("");
        log.info("  # Override GET /api/users/999 at runtime");
        log.info("  curl -X POST http://localhost:{}/__admin/mappings \\", PORT);
        log.info("       -H 'Content-Type: application/json' \\");
        log.info("       -d '{{\"request\":{{\"method\":\"GET\",\"urlPath\":\"/api/users/999\"}}," +
                "\"response\":{{\"status\":200,\"jsonBody\":{{\"id\":999,\"name\":\"Custom\"}}}}," +
                "\"priority\":1}}'");
        log.info("");
        log.info("  # Switch scenario state");
        log.info("  curl -X PUT http://localhost:{}/__admin/scenarios/getUser/state \\", PORT);
        log.info("       -H 'Content-Type: application/json' \\");
        log.info("       -d '{{\"state\":\"adminUser\"}}'");
        log.info("");
        log.info("  # List all registered stubs");
        log.info("  curl http://localhost:{}/__admin/mappings", PORT);
        log.info("");
        log.info("  # Reset stubs to original");
        log.info("  curl -X POST http://localhost:{}/__admin/mappings/reset", PORT);
    }
}
