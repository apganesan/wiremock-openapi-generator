package com.agfa.orbis.common.mockengine.impl;

import com.agfa.orbis.common.mockengine.api.StubClient;
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
 * {@link StubClient} implementation backed by the WireMock Admin REST API.
 *
 * <p>Override stubs use {@code priority=1} so they always win over
 * generated stubs ({@code priority=5}).
 */
public class WireMockAdminClient implements StubClient {

    private static final Logger LOG = LoggerFactory.getLogger(WireMockAdminClient.class);

    private static final int    OVERRIDE_PRIORITY = 1;
    private static final String JSON_CONTENT_TYPE = "application/json";

    private final String       adminUrl;
    private final ObjectMapper mapper;

    public WireMockAdminClient(String host, int port) {
        this.adminUrl = "http://" + host + ":" + port + "/__admin";
        this.mapper   = new ObjectMapper();
        LOG.debug("WireMockAdminClient -> {}", adminUrl);
    }

    // ── find stub ──────────────────────────────────────────────────────────────

    @Override
    public JsonNode findStub(String method, String urlPattern) throws IOException {
        JsonNode all = mapper.readTree(httpGet(adminUrl + "/mappings"));
        for (JsonNode m : all.path("mappings")) {
            if (matchesRequest(m, method, urlPattern)) return m;
        }
        return null;
    }

    // ── single unified override ────────────────────────────────────────────────

    /**
     * Core override logic — one method for all cases:
     * <ul>
     *   <li>{@code exampleIndex != null} → use that example from {@code metadata.examples}</li>
     *   <li>{@code exampleIndex == null} → use whatever the server is currently serving</li>
     *   <li>{@code patches} (possibly empty) applied on top of the base body</li>
     * </ul>
     */
    @Override
    public void applyOverride(String method, String urlPattern,
                              Integer exampleIndex, Map<String, Object> patches) throws IOException {
        LOG.info("applyOverride {} {} — exampleIndex={}, patches={}", method, urlPattern, exampleIndex, patches.keySet());

        ObjectNode baseBody;

        if (exampleIndex != null) {
            // Read from the generated stub's metadata.examples (never the active override)
            JsonNode generated = findGeneratedStub(method, urlPattern);
            if (generated == null) {
                throw new IllegalStateException("No generated stub found for " + method + " " + urlPattern);
            }
            JsonNode examples = generated.path("metadata").path("examples");
            if (exampleIndex < 0 || exampleIndex >= examples.size()) {
                throw new IndexOutOfBoundsException(
                        "Index " + exampleIndex + " out of bounds (found " + examples.size() + " examples)");
            }
            JsonNode exBody = examples.get(exampleIndex).path("body");
            String   name   = examples.get(exampleIndex).path("name").asText();
            LOG.info("  Base: example[{}] '{}'", exampleIndex, name);
            baseBody = (ObjectNode) mapper.readTree(exBody.toString());
        } else {
            // Patch on top of whatever WireMock is currently serving
            JsonNode active = findActiveStub(method, urlPattern);
            if (active == null) {
                throw new IllegalStateException("No stub found for " + method + " " + urlPattern);
            }
            JsonNode jsonBody = active.path("response").path("jsonBody");
            if (jsonBody.isMissingNode()) {
                throw new IllegalStateException("Active stub has no jsonBody for " + method + " " + urlPattern);
            }
            LOG.info("  Base: currently active stub (priority={})", active.path("priority").asInt());
            baseBody = (ObjectNode) mapper.readTree(jsonBody.toString());
        }

        // Apply patches (may be empty — that's fine, pure example selection)
        for (Map.Entry<String, Object> e : patches.entrySet()) {
            setAtPath(baseBody, e.getKey(), e.getValue());
        }

        postOverride(method, urlPattern, baseBody);
        LOG.info("  ✅  Override applied.");
    }

    // ── array operations ───────────────────────────────────────────────────────

    @Override
    public void patchArrayItem(String method, String urlPattern,
                               int index, Map<String, Object> updates) throws IOException {
        LOG.info("patchArrayItem[{}] {} {}", index, method, urlPattern);
        ObjectNode stub  = requireActiveCopy(method, urlPattern);
        ArrayNode  array = requireArray(stub, method, urlPattern);
        requireInBounds(array, index);
        ObjectNode item = (ObjectNode) array.get(index);
        updates.forEach((k, v) -> item.set(k, mapper.valueToTree(v)));
        stub.put("priority", OVERRIDE_PRIORITY);
        httpPost(adminUrl + "/mappings", stub.toString());
        LOG.info("  ✅  Array item [{}] patched.", index);
    }

    @Override
    public void addArrayItem(String method, String urlPattern, Object item) throws IOException {
        LOG.info("addArrayItem {} {}", method, urlPattern);
        ObjectNode stub  = requireActiveCopy(method, urlPattern);
        ArrayNode  array = requireArray(stub, method, urlPattern);
        array.add(mapper.valueToTree(item));
        stub.put("priority", OVERRIDE_PRIORITY);
        httpPost(adminUrl + "/mappings", stub.toString());
        LOG.info("  ✅  Item appended (size={}).", array.size());
    }

    @Override
    public void removeArrayItem(String method, String urlPattern, int index) throws IOException {
        LOG.info("removeArrayItem[{}] {} {}", index, method, urlPattern);
        ObjectNode stub  = requireActiveCopy(method, urlPattern);
        ArrayNode  array = requireArray(stub, method, urlPattern);
        requireInBounds(array, index);
        array.remove(index);
        stub.put("priority", OVERRIDE_PRIORITY);
        httpPost(adminUrl + "/mappings", stub.toString());
        LOG.info("  ✅  Array item [{}] removed (size={}).", index, array.size());
    }

    // ── reset ──────────────────────────────────────────────────────────────────

    @Override
    public void reset() throws IOException {
        LOG.info("Resetting stubs to generated state …");
        httpPost(adminUrl + "/mappings/reset", "");
        LOG.info("  ✅  Stubs reset.");
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /** Post a priority-1 override stub with the given body. */
    private void postOverride(String method, String urlPattern, ObjectNode body) throws IOException {
        ObjectNode stub     = buildStub(method, urlPattern, body, 200);
        stub.put("priority", OVERRIDE_PRIORITY);
        mapper.readTree(httpPost(adminUrl + "/mappings", stub.toString()));
    }

    /**
     * Original generated stub (has {@code metadata.examples}).
     * Always priority-5; never mutated by runtime overrides.
     */
    private JsonNode findGeneratedStub(String method, String urlPattern) throws IOException {
        JsonNode all = mapper.readTree(httpGet(adminUrl + "/mappings"));
        for (JsonNode m : all.path("mappings")) {
            if (!matchesRequest(m, method, urlPattern)) continue;
            if (!m.path("metadata").path("examples").isMissingNode()) return m;
        }
        return null;
    }

    /** Stub with the lowest priority number = what WireMock is actually serving right now. */
    private JsonNode findActiveStub(String method, String urlPattern) throws IOException {
        JsonNode all = mapper.readTree(httpGet(adminUrl + "/mappings"));
        JsonNode active      = null;
        int      bestPriority = Integer.MAX_VALUE;
        for (JsonNode m : all.path("mappings")) {
            if (!matchesRequest(m, method, urlPattern)) continue;
            int p = m.path("priority").asInt(Integer.MAX_VALUE);
            if (p < bestPriority) { bestPriority = p; active = m; }
        }
        return active;
    }

    private boolean matchesRequest(JsonNode mapping, String method, String urlPattern) {
        JsonNode req  = mapping.path("request");
        String   meth = req.path("method").asText();
        String   url  = req.has("urlPath")
                ? req.path("urlPath").asText()
                : req.path("urlPathPattern").asText();
        return method.equalsIgnoreCase(meth) && urlPattern.equals(url);
    }

    /** Navigate dot-path and set leaf value, creating intermediate objects as needed. */
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

    private ObjectNode buildStub(String method, String urlPattern, Object body, int status) {
        ObjectNode stub    = mapper.createObjectNode();
        ObjectNode request = mapper.createObjectNode();
        request.put("method", method.toUpperCase());
        boolean isPattern = urlPattern.contains("(") || urlPattern.contains("[");
        request.put(isPattern ? "urlPathPattern" : "urlPath", urlPattern);
        stub.set("request", request);

        ObjectNode response = mapper.createObjectNode();
        response.put("status", status);
        ObjectNode headers  = mapper.createObjectNode();
        headers.put("Content-Type", JSON_CONTENT_TYPE);
        response.set("headers", headers);
        if (body != null) response.set("jsonBody", mapper.valueToTree(body));
        stub.set("response", response);
        return stub;
    }

    private ObjectNode requireActiveCopy(String method, String urlPattern) throws IOException {
        JsonNode existing = findActiveStub(method, urlPattern);
        if (existing == null) throw new IllegalStateException("No stub found for " + method + " " + urlPattern);
        return (ObjectNode) mapper.readTree(existing.toString());
    }

    private ArrayNode requireArray(ObjectNode stub, String method, String urlPattern) {
        JsonNode body = stub.path("response").path("jsonBody");
        if (!body.isArray()) throw new IllegalStateException(
                "jsonBody for " + method + " " + urlPattern + " is not an array.");
        return (ArrayNode) body;
    }

    private void requireInBounds(ArrayNode array, int index) {
        if (index < 0 || index >= array.size()) throw new IndexOutOfBoundsException(
                "Index " + index + " out of bounds (size=" + array.size() + ")");
    }

    // ── raw HTTP ───────────────────────────────────────────────────────────────

    private String httpGet(String url) throws IOException {
        return readResponse(openConnection(url, "GET"));
    }

    private String httpPost(String url, String body) throws IOException {
        HttpURLConnection conn = openConnection(url, "POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", JSON_CONTENT_TYPE);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
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
        String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        if (code >= 400) throw new IOException("HTTP " + code + ": " + body);
        return body;
    }
}
