package com.agfa.orbis.common.mockengine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;


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

    // ── 5. Error scenarios ────────────────────────────────────────────────────

    @Test
    void switchTo404_returnsNotFound() throws Exception {
        wiremock.switchToError("getMedicationById", 404);
        assertEquals(404, GET("/med/1"));
    }

    @Test
    void switchTo500_returnsServerError() throws Exception {
        wiremock.switchToError("getMedicationById", 500);
        assertEquals(500, GET("/med/1"));
    }

    @Test
    void afterReset_backToSuccess() throws Exception {
        wiremock.switchToError("getMedicationById", 404);
        assertEquals(404, GET("/med/1"));
        wiremock.resetStubs();                // resets scenario back to "Started"
        assertEquals(200, GET("/med/1"));
    }

    // ── 6. Payload-driven POST (one payload → 201, another → 500) ──────────────

    @Test
    void postDefault_returns201() throws Exception {
        // No override — the generated createMedication stub serves its 201 example
        assertEquals(201, POST("/med", "{\"id\":42,\"name\":\"Ibuprofen\",\"type\":\"analgesic\"}"));
    }

    @Test
    void postPayloadDriven_createdVsError() throws Exception {
        // Payload with name="Ibuprofen" → 201 using the spec's default example body
        wiremock.forStub("POST", "/med")
                .whenFieldEquals("name", "Ibuprofen")
                .example(0)
                .respondStatus(201)
                .apply();

        // Payload with name="Poison" → 500
        wiremock.forStub("POST", "/med")
                .whenFieldEquals("name", "Poison")
                .respondStatus(500)
                .withResponseBody(Map.of("code", 500, "message", "Cannot create medication"))
                .apply();

        assertEquals(201, POST("/med", "{\"name\":\"Ibuprofen\",\"type\":\"analgesic\"}"));
        String created = POST_BODY("/med", "{\"name\":\"Ibuprofen\",\"type\":\"analgesic\"}");
        assertTrue(created.contains("\"name\":\"Ibuprofen\""));     // from default example
        assertTrue(created.contains("\"linkType\":\"FORMULARY\"")); // nested default value
        assertEquals(500, POST("/med", "{\"name\":\"Poison\"}"));
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
