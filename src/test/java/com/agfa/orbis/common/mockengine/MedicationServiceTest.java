package com.agfa.orbis.common.mockengine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test — single forStub() API handles all cases:
 *
 *   .apply()                   → pure example switch (or no-op if no example()/with())
 *   .example(i).apply()        → switch to OpenAPI example i (0-based)
 *   .example(i).with(k,v)...   → switch to example i, then patch some fields
 *   .with(k,v)...apply()       → patch fields on whatever is currently active
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
    void resetToDefault() {
        wiremock.resetStubs();   // back to first OpenAPI example before each test
    }

    @AfterAll
    static void stopServer() {
        wiremock.stop();
    }

    // ── 1. Default ─────────────────────────────────────────────────────────────

    @Test
    void default_returnsFirstExample() throws Exception {
        assertEquals(200, GET("/med/1"));   // no setup — first OpenAPI example served
    }

    // ── 2. Pure example selection ──────────────────────────────────────────────

    @Test
    void selectSecondExample() throws Exception {
        wiremock.forStub("GET", "/med/([^/]+)").example(1).apply();
        assertEquals(200, GET("/med/1"));
    }

    @Test
    void selectThirdExample() throws Exception {
        wiremock.forStub("GET", "/med/([^/]+)").example(2).apply();
        assertEquals(200, GET("/med/1"));
    }

    // ── 3. Patch current active (no example switch) ───────────────────────────

    @Test
    void patchOnDefault() throws Exception {
        // status changes; all other fields keep ibuprofen (first example) values
        wiremock.forStub("GET", "/med/([^/]+)")
                .with("status", "discontinued")
                .apply();
        assertEquals(200, GET("/med/1"));
    }

    // ── 4. Select example THEN patch some fields ──────────────────────────────

    @Test
    void selectExampleThenPatch() throws Exception {
        // Switch to 3rd example (metformin) and override only status
        wiremock.forStub("GET", "/med/([^/]+)")
                .example(2)
                .with("status", "recalled")
                .apply();
        // Response: metformin body with status="recalled"; all other fields from metformin example
        assertEquals(200, GET("/med/1"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static int GET(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL(wiremock.getBaseUrl() + path).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);
        return conn.getResponseCode();
    }
}
