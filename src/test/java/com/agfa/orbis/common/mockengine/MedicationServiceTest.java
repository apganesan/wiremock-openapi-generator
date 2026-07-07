package com.agfa.orbis.common.mockengine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;


@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MedicationServiceTest {

    static WireMockIntegrationSupport wiremock;

    @BeforeAll
    static void startServer() {
        wiremock = WireMockIntegrationSupport.builder()
                .openApiFile("src/test/resources/openapi.yaml")
                .mappingsDir("target/wiremock/mappings")   // <-- add this
                .mockServer(new DockerWireMockServer())
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

    // ── 1. Normal flow — default example served as-is ─────────────────────────

    @Test
    @Order(1)
    void get_returnsDefaultExample() throws Exception {
        // The stub matches the regex /med/([^/]+); the REQUEST must use a concrete id.
        // A random id proves any id resolves to the same default example.
        String path = "/med/" + ThreadLocalRandom.current().nextInt(1, 10_000);
        assertEquals(200, GET(path));
        String body = GET_BODY(path);
        assertTrue(body.contains("\"name\":\"Ibuprofen\""));
        assertTrue(body.contains("\"type\":\"analgesic\""));
        assertTrue(body.contains("\"linkType\":\"FORMULARY\""));
    }

    // ── 2. Change an existing default value ───────────────────────────────────

    @Test
    @Order(2)
    void get_changeDefaultValue() throws Exception {
        // Override the default example's "type"; every other field keeps its value
        wiremock.forStub("GET", "/med/([^/]+)")
                .example(0)
                .with("type", "antibiotic")
                .apply();
        // Any concrete id matches the same stub regex
        String path = "/med/" + ThreadLocalRandom.current().nextInt(1, 10_000);
        assertEquals(200, GET(path));
        String body = GET_BODY(path);
        assertTrue(body.contains("\"type\":\"antibiotic\""));   // changed
        assertTrue(body.contains("\"name\":\"Ibuprofen\""));    // untouched
    }

    // ── 3. Change a nested default value ──────────────────────────────────────

    @Test
    @Order(3)
    void get_changeNestedValue() throws Exception {
        // Override a nested field via dot-path; every other field keeps its value
        wiremock.forStub("GET", "/med/([^/]+)")
                .example(0)
                .with("linkRefDto.linkId", 302)
                .apply();
        // Any concrete id matches the same stub regex
        String path = "/med/" + ThreadLocalRandom.current().nextInt(1, 10_000);
        assertEquals(200, GET(path));
        String body = GET_BODY(path);
        assertTrue(body.contains("\"linkId\":302"));            // changed
        assertFalse(body.contains("\"linkId\":101"));           // old value gone
        assertTrue(body.contains("\"linkType\":\"FORMULARY\""));// untouched
        assertTrue(body.contains("\"name\":\"Ibuprofen\""));    // untouched
    }

    // ── 6. Payload-driven POST (one payload → 201, another → 500) ──────────────

    @Test
    @Order(4)
    void postDefault_returns201() throws Exception {
        // No override — the generated createMedication stub serves its 201 example
        assertEquals(201, POST("/med", "{\"id\":42,\"name\":\"Ibuprofen\",\"type\":\"analgesic\"}"));
    }

    @Test
    @Order(5)
    void postPayloadDriven_createdVsError() throws Exception {
        // Payload with name="Ibuprofen" → 201 using the spec's default example body
        wiremock.forStub("POST", "/med")
                .whenFieldEquals("name", "Ibuprofen")
                .example(0)
                .respondStatus(201)
                .apply();

        // Payload with name="Poison" → 500 (spec's 500 body served automatically)
        wiremock.forStub("POST", "/med")
                .whenFieldEquals("name", "Poison")
                .respondStatus(500)
                .apply();

        assertEquals(201, POST("/med", "{\"name\":\"Ibuprofen\",\"type\":\"analgesic\"}"));
        String created = POST_BODY("/med", "{\"name\":\"Ibuprofen\",\"type\":\"analgesic\"}");
        assertTrue(created.contains("\"name\":\"Ibuprofen\""));     // from default example
        assertTrue(created.contains("\"linkType\":\"FORMULARY\"")); // nested default value
        assertEquals(500, POST("/med", "{\"name\":\"Poison\"}"));
    }

    // ── 7. Status-driven body: spec's example served by default, patch one field ──

    @Test
    @Order(6)
    void post500_usesSpecBodyByDefault() throws Exception {
        // No explicit body / example: selecting status 500 auto-serves the spec's 500 example
        wiremock.forStub("POST", "/med")
                .whenFieldEquals("name", "Poison")
                .respondStatus(500)
                .apply();

        assertEquals(500, POST("/med", "{\"name\":\"Poison\"}"));
        String body = POST_BODY("/med", "{\"name\":\"Poison\"}");
        assertTrue(body.contains("\"code\":500"));                              // from spec's 500 example
        assertTrue(body.contains("Failed to persist medication record"));      // spec's default message
    }

    @Test
    @Order(7)
    void post500_patchMessageKeepsSpecBody() throws Exception {
        // Base = spec's 500 example; only the message field is overridden
        wiremock.forStub("POST", "/med")
                .whenFieldEquals("name", "Poison")
                .respondStatus(500)
                .with("message", "Cannot create medication")
                .apply();

        assertEquals(500, POST("/med", "{\"name\":\"Poison\"}"));
        String body = POST_BODY("/med", "{\"name\":\"Poison\"}");
        assertTrue(body.contains("\"code\":500"));                             // untouched, from spec
        assertTrue(body.contains("\"message\":\"Cannot create medication\"")); // patched
        assertFalse(body.contains("Failed to persist medication record"));     // old message gone
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

    private static String GET_BODY(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL(wiremock.getBaseUrl() + path).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);

        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) {
            return "";
        }
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int POST(String path, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL(wiremock.getBaseUrl() + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        return conn.getResponseCode();
    }

    private static String POST_BODY(String path, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL(wiremock.getBaseUrl() + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) {
            return "";
        }
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
