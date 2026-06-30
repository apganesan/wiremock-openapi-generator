package com.example.mockgen;

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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Provides a Java client for the WireMock Admin API that enables runtime modification
 * of stub mappings without restarting the server.
 * <p>
 * All override stubs receive {@code priority = 1}, which is higher than the default
 * priority of generated stubs ({@code priority = 5}), so overrides always take
 * precedence.
 */
public class EnhancedRuntimeStubManager {

    private static final Logger log = LoggerFactory.getLogger(EnhancedRuntimeStubManager.class);

    private static final int OVERRIDE_PRIORITY = 1;
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final String adminBaseUrl;
    private final ObjectMapper objectMapper;

    /**
     * @param host WireMock host, e.g. {@code "localhost"}
     * @param port WireMock port, e.g. {@code 8080}
     */
    public EnhancedRuntimeStubManager(String host, int port) {
        this.adminBaseUrl = "http://" + host + ":" + port + "/__admin";
        this.objectMapper = new ObjectMapper();
        log.info("Runtime stub manager connected to {}", adminBaseUrl);
    }

    // -------------------------------------------------------------------------
    // Query helpers
    // -------------------------------------------------------------------------

    /**
     * Fetch and return all currently registered stub mappings as a JSON node.
     *
     * @return root JSON node from {@code /__admin/mappings}
     */
    public JsonNode getAllStubs() throws IOException {
        String responseBody = httpGet(adminBaseUrl + "/mappings");
        return objectMapper.readTree(responseBody);
    }

    /**
     * Find a stub by HTTP method and URL pattern string.
     *
     * @param method     HTTP method (GET, POST, …)
     * @param urlPattern exact urlPath or urlPathPattern string used in the stub request matcher
     * @return the stub JSON node, or {@code null} if not found
     */
    public JsonNode getExistingStub(String method, String urlPattern) throws IOException {
        JsonNode allMappings = getAllStubs();
        JsonNode mappings = allMappings.path("mappings");
        for (JsonNode mapping : mappings) {
            JsonNode request = mapping.path("request");
            String stubMethod = request.path("method").asText();
            String stubUrl = request.has("urlPath")
                    ? request.path("urlPath").asText()
                    : request.path("urlPathPattern").asText();

            if (method.equalsIgnoreCase(stubMethod) && urlPattern.equals(stubUrl)) {
                return mapping;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Override / replace entire response
    // -------------------------------------------------------------------------

    /**
     * Create (or replace) a stub that completely overrides the response for the given
     * method + URL pattern.  The new stub gets priority 1 so it wins over generated stubs.
     *
     * @param method      HTTP method (GET, POST, …)
     * @param urlPattern  URL path or regex pattern
     * @param responseBody object to serialize as the JSON response body
     * @return the stub ID assigned by WireMock
     */
    public String overrideStub(String method, String urlPattern, Object responseBody)
            throws IOException {
        log.info("Overriding stub: {} {}", method, urlPattern);

        ObjectNode stub = buildBaseStub(method, urlPattern, responseBody, 200);
        stub.put("priority", OVERRIDE_PRIORITY);

        String responseJson = httpPost(adminBaseUrl + "/mappings", stub.toString());
        JsonNode responseNode = objectMapper.readTree(responseJson);
        String id = responseNode.path("id").asText();
        log.info("  ✅  Stub override registered with id={}", id);
        return id;
    }

    // -------------------------------------------------------------------------
    // Partial (patch) operations
    // -------------------------------------------------------------------------

    /**
     * Retrieve an existing stub, update a single property identified by a simple
     * dot-notation JSON path (e.g. {@code "profile.location"}), and re-register
     * the stub with priority 1.
     *
     * @param method      HTTP method
     * @param urlPattern  URL path or regex pattern
     * @param jsonPath    dot-separated path to the property to update (e.g. {@code "name"} or {@code "profile.location"})
     * @param newValue    new value to set at that path
     */
    public void patchStubProperty(String method, String urlPattern, String jsonPath, Object newValue)
            throws IOException {
        log.info("Patching stub property '{}' for {} {}", jsonPath, method, urlPattern);

        JsonNode existing = getExistingStub(method, urlPattern);
        if (existing == null) {
            throw new IllegalStateException(
                    "No stub found for " + method + " " + urlPattern + " — cannot patch.");
        }

        // Deep-copy so we can mutate
        ObjectNode stubCopy = (ObjectNode) objectMapper.readTree(existing.toString());

        // Navigate to the jsonBody and apply the update
        JsonNode jsonBody = stubCopy.path("response").path("jsonBody");
        if (jsonBody.isMissingNode()) {
            throw new IllegalStateException("Stub has no jsonBody — cannot patch property.");
        }

        updateJsonPath((ObjectNode) jsonBody, jsonPath, newValue);

        // Re-register with priority 1
        stubCopy.put("priority", OVERRIDE_PRIORITY);
        String responseJson = httpPost(adminBaseUrl + "/mappings", stubCopy.toString());
        JsonNode responseNode = objectMapper.readTree(responseJson);
        log.info("  ✅  Property '{}' patched; new stub id={}", jsonPath, responseNode.path("id").asText());
    }

    // -------------------------------------------------------------------------
    // Array manipulation
    // -------------------------------------------------------------------------

    /**
     * Update an item inside an array response body by its zero-based index.
     *
     * @param method      HTTP method
     * @param urlPattern  URL path or regex pattern
     * @param itemIndex   zero-based index of the array element to update
     * @param updates     map of field name → new value to apply to the element
     */
    public void patchArrayItem(String method, String urlPattern, int itemIndex,
                               Map<String, Object> updates) throws IOException {
        log.info("Patching array item [{}] for {} {}", itemIndex, method, urlPattern);

        JsonNode existing = getExistingStub(method, urlPattern);
        if (existing == null) {
            throw new IllegalStateException("No stub found for " + method + " " + urlPattern);
        }

        ObjectNode stubCopy = (ObjectNode) objectMapper.readTree(existing.toString());
        JsonNode jsonBody = stubCopy.path("response").path("jsonBody");

        if (!jsonBody.isArray()) {
            throw new IllegalStateException("Response jsonBody is not an array.");
        }

        ArrayNode array = (ArrayNode) jsonBody;
        if (itemIndex < 0 || itemIndex >= array.size()) {
            throw new IndexOutOfBoundsException(
                    "Array index " + itemIndex + " out of bounds (size=" + array.size() + ")");
        }

        ObjectNode item = (ObjectNode) array.get(itemIndex);
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            item.set(entry.getKey(), objectMapper.valueToTree(entry.getValue()));
        }

        stubCopy.put("priority", OVERRIDE_PRIORITY);
        httpPost(adminBaseUrl + "/mappings", stubCopy.toString());
        log.info("  ✅  Array item [{}] updated.", itemIndex);
    }

    /**
     * Append a new item to an array response body.
     *
     * @param method      HTTP method
     * @param urlPattern  URL path or regex pattern
     * @param newItem     object to append to the array
     */
    public void addArrayItem(String method, String urlPattern, Object newItem) throws IOException {
        log.info("Adding item to array for {} {}", method, urlPattern);

        JsonNode existing = getExistingStub(method, urlPattern);
        if (existing == null) {
            throw new IllegalStateException("No stub found for " + method + " " + urlPattern);
        }

        ObjectNode stubCopy = (ObjectNode) objectMapper.readTree(existing.toString());
        JsonNode jsonBody = stubCopy.path("response").path("jsonBody");

        if (!jsonBody.isArray()) {
            throw new IllegalStateException("Response jsonBody is not an array.");
        }

        ArrayNode array = (ArrayNode) jsonBody;
        array.add(objectMapper.valueToTree(newItem));

        stubCopy.put("priority", OVERRIDE_PRIORITY);
        httpPost(adminBaseUrl + "/mappings", stubCopy.toString());
        log.info("  ✅  New item appended to array (new size={}).", array.size());
    }

    /**
     * Remove an item from an array response body by its zero-based index.
     *
     * @param method      HTTP method
     * @param urlPattern  URL path or regex pattern
     * @param itemIndex   zero-based index of the element to remove
     */
    public void removeArrayItem(String method, String urlPattern, int itemIndex) throws IOException {
        log.info("Removing array item [{}] from {} {}", itemIndex, method, urlPattern);

        JsonNode existing = getExistingStub(method, urlPattern);
        if (existing == null) {
            throw new IllegalStateException("No stub found for " + method + " " + urlPattern);
        }

        ObjectNode stubCopy = (ObjectNode) objectMapper.readTree(existing.toString());
        JsonNode jsonBody = stubCopy.path("response").path("jsonBody");

        if (!jsonBody.isArray()) {
            throw new IllegalStateException("Response jsonBody is not an array.");
        }

        ArrayNode array = (ArrayNode) jsonBody;
        if (itemIndex < 0 || itemIndex >= array.size()) {
            throw new IndexOutOfBoundsException(
                    "Array index " + itemIndex + " out of bounds (size=" + array.size() + ")");
        }

        array.remove(itemIndex);

        stubCopy.put("priority", OVERRIDE_PRIORITY);
        httpPost(adminBaseUrl + "/mappings", stubCopy.toString());
        log.info("  ✅  Array item [{}] removed (new size={}).", itemIndex, array.size());
    }

    // -------------------------------------------------------------------------
    // Scenario management
    // -------------------------------------------------------------------------

    /**
     * Transition a named scenario to the given state.
     *
     * @param scenarioName name of the WireMock scenario
     * @param state        desired state (must match a {@code requiredScenarioState} in a stub)
     */
    public void switchScenarioState(String scenarioName, String state) throws IOException {
        log.info("Switching scenario '{}' to state '{}'", scenarioName, state);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("state", state);

        httpPut(adminBaseUrl + "/scenarios/" + scenarioName + "/state", body.toString());
        log.info("  ✅  Scenario '{}' is now in state '{}'.", scenarioName, state);
    }

    // -------------------------------------------------------------------------
    // Reset
    // -------------------------------------------------------------------------

    /**
     * Reset all stub mappings to the state persisted on disk (i.e. the generated stubs).
     * Any runtime overrides added via the Admin API will be removed.
     */
    public void resetToOriginal() throws IOException {
        log.info("Resetting all stub mappings to original state ...");
        httpPost(adminBaseUrl + "/mappings/reset", "");
        log.info("  ✅  All stubs reset to original.");
    }

    // -------------------------------------------------------------------------
    // JSON path utility
    // -------------------------------------------------------------------------

    /**
     * Navigate a dot-separated JSON path and set the leaf value.
     * <p>
     * Example: path {@code "profile.location"} on the node
     * {@code {"profile": {"location": "NYC"}}} will update {@code location}.
     *
     * @param root      root ObjectNode to navigate
     * @param jsonPath  dot-separated path
     * @param newValue  new value to set at the leaf
     */
    private void updateJsonPath(ObjectNode root, String jsonPath, Object newValue) {
        String[] parts = jsonPath.split("\\.");
        ObjectNode current = root;

        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode next = current.get(parts[i]);
            if (next == null || !next.isObject()) {
                // Create missing intermediate nodes
                ObjectNode intermediate = objectMapper.createObjectNode();
                current.set(parts[i], intermediate);
                current = intermediate;
            } else {
                current = (ObjectNode) next;
            }
        }

        String leafKey = parts[parts.length - 1];
        current.set(leafKey, objectMapper.valueToTree(newValue));
    }

    // -------------------------------------------------------------------------
    // Private HTTP helpers
    // -------------------------------------------------------------------------

    private ObjectNode buildBaseStub(String method, String urlPattern, Object responseBody,
                                     int statusCode) {
        ObjectNode stub = objectMapper.createObjectNode();

        ObjectNode request = objectMapper.createObjectNode();
        request.put("method", method.toUpperCase());
        if (urlPattern.contains("(") || urlPattern.contains("[")) {
            request.put("urlPathPattern", urlPattern);
        } else {
            request.put("urlPath", urlPattern);
        }
        stub.set("request", request);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", statusCode);

        ObjectNode headers = objectMapper.createObjectNode();
        headers.put("Content-Type", CONTENT_TYPE_JSON);
        response.set("headers", headers);

        if (responseBody != null) {
            response.set("jsonBody", objectMapper.valueToTree(responseBody));
        }
        stub.set("response", response);

        return stub;
    }

    private String httpGet(String url) throws IOException {
        HttpURLConnection conn = openConnection(url, "GET");
        return readResponse(conn);
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

    private HttpURLConnection openConnection(String urlString, String method) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);
        return conn;
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        int responseCode = conn.getResponseCode();
        java.io.InputStream is = (responseCode < 400)
                ? conn.getInputStream()
                : conn.getErrorStream();
        if (is == null) return "";
        byte[] bytes = is.readAllBytes();
        String body = new String(bytes, StandardCharsets.UTF_8);
        if (responseCode >= 400) {
            throw new IOException("HTTP " + responseCode + ": " + body);
        }
        return body;
    }
}
