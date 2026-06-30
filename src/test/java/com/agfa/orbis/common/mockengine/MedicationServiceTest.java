package com.agfa.orbis.common.mockengine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates two ways to return different responses for the same request.
 *
 * ┌──────────────────┬─────────────────────────────────────────────────────────┐
 * │ Mechanism        │ When to use                                             │
 * ├──────────────────┼─────────────────────────────────────────────────────────┤
 * │ forStub().with() │ Each TEST needs a different static response.            │
 * │                  │ @BeforeEach resets to spec defaults; each test          │
 * │                  │ overrides only what it cares about.                     │
 * ├──────────────────┼─────────────────────────────────────────────────────────┤
 * │ forSequence()    │ One TEST needs the SAME endpoint to return different    │
 * │                  │ payloads on successive calls (polling, retry, state     │
 * │                  │ transitions).                                           │
 * └──────────────────┴─────────────────────────────────────────────────────────┘
 */
class MedicationServiceTest {

    static WireMockIntegrationSupport wiremock;

    @BeforeAll
    static void startServer() {
        wiremock = WireMockIntegrationSupport.builder()
                .openApiFile("src/test/resources/openapi.yaml")
                .build()
                .start();
    }

    @BeforeEach
    void resetToSpecDefaults() {
        wiremock.resetStubs();   // each test starts from OpenAPI example values
    }

    @AfterAll
    static void stopServer() {
        wiremock.stop();
    }

    // =========================================================================
    // Mechanism 1 — forStub().with()
    // Different test = different static response; untouched fields keep spec values
    // =========================================================================

    @Test
    void default_returnsSpecExample() throws Exception {
        // No override — response comes straight from the OpenAPI example
        assertEquals(200, GET("/med/1"));
    }

    @Test
    void context_active_medication() {
        // Only 'status' changes; name, dosage, id … keep their OpenAPI example values
        wiremock.forStub("GET", "/med/([^/]+)")
                .with("status", "active")
                .apply();
    }

    @Test
    void context_discontinued_medication() {
        wiremock.forStub("GET", "/med/([^/]+)")
                .with("status", "discontinued")
                .with("name", "OldDrug 200mg")   // two fields change, rest stays
                .apply();
    }

    // =========================================================================
    // Mechanism 2 — forSequence()
    // Same request, different response on each successive call (scenario / state machine)
    // =========================================================================

    @Test
    void sequence_pollingStatusChange() throws Exception {
        Map<String, Object> active = new LinkedHashMap<>();
        active.put("id", "med-001");
        active.put("status", "active");

        Map<String, Object> discontinued = new LinkedHashMap<>();
        discontinued.put("id", "med-001");
        discontinued.put("status", "discontinued");

        // Register: 1st call → active, 2nd call → discontinued, then cycles
        wiremock.forSequence("med-status-flow", "GET", "/med/([^/]+)")
                .thenReturn(active)
                .thenReturn(discontinued)
                .register();

        // Same URL, different payload each time
        assertEquals(200, GET("/med/med-001"));   // → active
        assertEquals(200, GET("/med/med-001"));   // → discontinued
        assertEquals(200, GET("/med/med-001"));   // → active again (cycles)
    }

    // =========================================================================

    private static int GET(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL(wiremock.getBaseUrl() + path).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);
        return conn.getResponseCode();
    }
}
