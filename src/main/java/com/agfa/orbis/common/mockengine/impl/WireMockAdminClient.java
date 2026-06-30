package com.agfa.orbis.common.mockengine.impl;
import com.agfa.orbis.common.mockengine.api.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * {@link StubClient} implementation that talks to the WireMock Admin REST API.
 *
 * <p>All override/patch stubs receive {@code priority = 1} so they always win over
 * the generated stubs ({@code priority = 5}).
 *
 * <p>The key design point is {@link #patchProperties}: it reads the existing stub (which
 * carries the full OpenAPI example body), applies only the supplied property changes, and
 * re-registers the stub — so untouched fields keep their spec-generated values.
 */
public class WireMockAdminClient implements StubClient {

    private static final Logger log = LoggerFactory.getLogger(WireMockAdminClient.class);

    private static final int    OVERRIDE_PRIORITY  = 1;
    private static final String CONTENT_TYPE_JSON  = "application/json";

    private final String adminUrl;
    private final ObjectMapper mapper;

    public WireMockAdminClient(String host, int port) {
        this.adminUrl = "http://" + host + ":" + port + "/__admin";
        this.mapper   = new ObjectMapper();
        log.debug("WireMockAdminClient connected to {}", adminUrl);
    }

    // -------------------------------------------------------------------------
    // StubClient — query
    // -------------------------------------------------------------------------

    @Override
    public JsonNode findStub(String method, String urlPattern) throws IOException {
        JsonNode all = mapper.readTree(httpGet(adminUrl + "/mappings"));
        for (JsonNode mapping : all.path("mappings")) {
            JsonNode req   = mapping.path("request");
            String   meth  = req.path("method").asText();
            String   url   = req.has("urlPath")
                    ? req.path("urlPath").asText()
                    : req.path("urlPathPattern").asText();
            if (method.equalsIgnoreCase(meth) && urlPattern.equals(url)) {
                return mapping;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // StubClient — full override
    // -------------------------------------------------------------------------

    @Override
    public String override(String method, String urlPattern, Object responseBody)
            throws IOException {
        log.info("Overriding stub: {} {}", method, urlPattern);

        ObjectNode stub = buildStub(method, urlPattern, responseBody, 200);
        stub.put("priority", OVERRIDE_PRIORITY);

        JsonNode response = mapper.readTree(httpPost(adminUrl + "/mappings", stub.toString()));
        String id = response.path("id").asText();
        log.info("  ✅  Override registered — id={}", id);
        return id;
    }

    // -------------------------------------------------------------------------
    // StubClient — partial property patch (KEY fix: preserves OpenAPI values)
    // -------------------------------------------------------------------------

    /**
     * Reads the existing stub, merges only {@code properties} into its {@code jsonBody},
     * and re-registers with priority 1.
     *
     * <p>Fields not listed in {@code properties} are unchanged — they keep the values
     * that were generated from the OpenAPI spec example.
     */
    @Override
    public void patchProperties(String method, String urlPattern,
                                Map<String, Object> properties) throws IOException {
        log.info("Patching {} properties on stub {} {}", properties.size(), method, urlPattern);

        JsonNode existing = findStub(method, urlPattern);
        if (existing == null) {
            throw new IllegalStateException(
                    "No stub found for " + method + " " + urlPattern);
        }

        // Deep-copy so we don't mutate the original
        ObjectNode stub = (ObjectNode) mapper.readTree(existing.toString());

        JsonNode jsonBody = stub.path("response").path("jsonBody");
        if (jsonBody.isMissingNode()) {
            throw new IllegalStateException("Stub has no jsonBody — cannot patch properties.");
        }

        // Apply each property change; remaining fields are untouched
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            setAtPath((ObjectNode) jsonBody, entry.getKey(), entry.getValue());
        }

        stub.put("priority", OVERRIDE_PRIORITY);
        httpPost(adminUrl + "/mappings", stub.toString());
        log.info("  ✅  Properties patched: {}", properties.keySet());
    }

    // -------------------------------------------------------------------------
    // StubClient — array operations
    // -------------------------------------------------------------------------

    @Override
    public void patchArrayItem(String method, String urlPattern, int index,
                               Map<String, Object> updates) throws IOException {
        log.info("Patching array item [{}] for {} {}", index, method, urlPattern);

        ObjectNode stub = requireStubCopy(method, urlPattern);
        ArrayNode array = requireArray(stub, method, urlPattern);
        requireInBounds(array, index);

        ObjectNode item = (ObjectNode) array.get(index);
        updates.forEach((k, v) -> item.set(k, mapper.valueToTree(v)));

        stub.put("priority", OVERRIDE_PRIORITY);
        httpPost(adminUrl + "/mappings", stub.toString());
        log.info("  ✅  Array item [{}] patched.", index);
    }

    @Override
    public void addArrayItem(String method, String urlPattern, Object item) throws IOException {
        log.info("Adding item to array for {} {}", method, urlPattern);

        ObjectNode stub  = requireStubCopy(method, urlPattern);
        ArrayNode  array = requireArray(stub, method, urlPattern);
        array.add(mapper.valueToTree(item));

        stub.put("priority", OVERRIDE_PRIORITY);
        httpPost(adminUrl + "/mappings", stub.toString());
        log.info("  ✅  Item appended (new size={}).", array.size());
    }

    @Override
    public void removeArrayItem(String method, String urlPattern, int index) throws IOException {
        log.info("Removing array item [{}] from {} {}", index, method, urlPattern);

        ObjectNode stub  = requireStubCopy(method, urlPattern);
        ArrayNode  array = requireArray(stub, method, urlPattern);
        requireInBounds(array, index);
        array.remove(index);

        stub.put("priority", OVERRIDE_PRIORITY);
        httpPost(adminUrl + "/mappings", stub.toString());
        log.info("  ✅  Array item [{}] removed (new size={}).", index, array.size());
    }

    // -------------------------------------------------------------------------
    // StubClient — scenario + reset
    // -------------------------------------------------------------------------

    @Override
    public void switchScenario(String scenarioName, String state) throws IOException {
        log.info("Switching scenario '{}' → '{}'", scenarioName, state);
        ObjectNode body = mapper.createObjectNode();
        body.put("state", state);
        httpPut(adminUrl + "/scenarios/" + scenarioName + "/state", body.toString());
        log.info("  ✅  Scenario '{}' is now '{}'.", scenarioName, state);
    }

    @Override
    public void registerSequence(String scenarioName, String method, String urlPattern,
                                 java.util.List<?> responses) throws IOException {
        if (responses == null || responses.isEmpty()) {
            throw new IllegalArgumentException("responses list must not be empty");
        }
        log.info("Registering sequence '{}' ({} responses) for {} {}",
                scenarioName, responses.size(), method, urlPattern);

        int last = responses.size() - 1;
        for (int i = 0; i <= last; i++) {
            String requiredState = (i == 0)    ? "Started"     : "state-" + i;
            String newState      = (i == last)  ? "Started"     : "state-" + (i + 1);

            ObjectNode stub = buildStub(method, urlPattern, responses.get(i), 200);
            stub.put("scenarioName",          scenarioName);
            stub.put("requiredScenarioState", requiredState);
            stub.put("newScenarioState",      newState);
            stub.put("priority",              OVERRIDE_PRIORITY);

            httpPost(adminUrl + "/mappings", stub.toString());
        }
        log.info("  ✅  Sequence '{}' registered ({} steps).", scenarioName, responses.size());
    }

    @Override
    public void reset() throws IOException {
        log.info("Resetting all stubs to generated state ...");
        httpPost(adminUrl + "/mappings/reset", "");
        log.info("  ✅  Stubs reset.");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Navigate a dot-separated JSON path and set the leaf value, creating intermediate
     * nodes as needed.  Example: path {@code "address.city"} sets {@code city} inside
     * the nested {@code address} object.
     */
    private void setAtPath(ObjectNode root, String dotPath, Object value) {
        String[]   parts   = dotPath.split("\\.");
        ObjectNode current = root;

        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode next = current.get(parts[i]);
            if (next == null || !next.isObject()) {
                ObjectNode node = mapper.createObjectNode();
                current.set(parts[i], node);
                current = node;
            } else {
                current = (ObjectNode) next;
            }
        }
        current.set(parts[parts.length - 1], mapper.valueToTree(value));
    }

    private ObjectNode buildStub(String method, String urlPattern,
                                  Object body, int statusCode) {
        ObjectNode stub    = mapper.createObjectNode();
        ObjectNode request = mapper.createObjectNode();
        request.put("method", method.toUpperCase());
        boolean isPattern = urlPattern.contains("(") || urlPattern.contains("[");
        request.put(isPattern ? "urlPathPattern" : "urlPath", urlPattern);
        stub.set("request", request);

        ObjectNode response = mapper.createObjectNode();
        response.put("status", statusCode);
        ObjectNode headers  = mapper.createObjectNode();
        headers.put("Content-Type", CONTENT_TYPE_JSON);
        response.set("headers", headers);
        if (body != null) {
            response.set("jsonBody", mapper.valueToTree(body));
        }
        stub.set("response", response);
        return stub;
    }

    private ObjectNode requireStubCopy(String method, String urlPattern) throws IOException {
        JsonNode existing = findStub(method, urlPattern);
        if (existing == null) {
            throw new IllegalStateException("No stub found for " + method + " " + urlPattern);
        }
        return (ObjectNode) mapper.readTree(existing.toString());
    }

    private ArrayNode requireArray(ObjectNode stub, String method, String urlPattern) {
        JsonNode body = stub.path("response").path("jsonBody");
        if (!body.isArray()) {
            throw new IllegalStateException(
                    "jsonBody for " + method + " " + urlPattern + " is not an array.");
        }
        return (ArrayNode) body;
    }

    private void requireInBounds(ArrayNode array, int index) {
        if (index < 0 || index >= array.size()) {
            throw new IndexOutOfBoundsException(
                    "Index " + index + " out of bounds (size=" + array.size() + ")");
        }
    }

    // -------------------------------------------------------------------------
    // Raw HTTP helpers
    // -------------------------------------------------------------------------

    private String httpGet(String url) throws IOException {
        return readResponse(openConnection(url, "GET"));
    }

    private String httpPost(String url, String body) throws IOException {
        HttpURLConnection conn = openConnection(url, "POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", CONTENT_TYPE_JSON);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
    }

    private void httpPut(String url, String body) throws IOException {
        HttpURLConnection conn = openConnection(url, "PUT");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", CONTENT_TYPE_JSON);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP PUT " + url + " returned " + code);
        }
    }

    private HttpURLConnection openConnection(String urlStr, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);
        return conn;
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        java.io.InputStream is = (code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return "";
        byte[] bytes = is.readAllBytes();
        String body = new String(bytes, StandardCharsets.UTF_8);
        if (code >= 400) throw new IOException("HTTP " + code + ": " + body);
        return body;
    }
}
