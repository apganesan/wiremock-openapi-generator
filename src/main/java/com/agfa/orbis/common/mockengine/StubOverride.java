package com.agfa.orbis.common.mockengine;

import com.agfa.orbis.common.mockengine.api.StubClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StubOverride {

    private final StubClient        client;
    private final String            method;
    private final String            urlPattern;
    private       Integer           exampleIndex = null;   // null = use currently active stub
    private final Map<String,Object> patches     = new LinkedHashMap<>();

    // Conditional (payload-driven) override state
    private final List<String> jsonPathMatchers = new ArrayList<>();
    private       Integer      status           = null;    // null = 200 when conditional
    private       Object       responseBody     = null;    // explicit body (overrides example)

    StubOverride(StubClient client, String method, String urlPattern) {
        this.client     = client;
        this.method     = method;
        this.urlPattern = urlPattern;
    }

    /**
     * Select which OpenAPI example to use as the base body (0 = first, 1 = second, …).
     * Fields set via {@link #with} are applied on top.
     */
    public StubOverride example(int index) {
        if (index < 0) throw new IndexOutOfBoundsException("Example index must be >= 0");
        this.exampleIndex = index;
        return this;
    }

    /**
     * Override one field by dot-path (e.g. {@code "status"} or {@code "address.city"}).
     * Only listed fields change; all others keep the base body's values.
     */
    public StubOverride with(String jsonPath, Object value) {
        patches.put(jsonPath, value);
        return this;
    }

    /**
     * Only respond to requests whose JSON body matches the given WireMock
     * {@code matchesJsonPath} expression. Multiple calls are ANDed together.
     * Marks this override as conditional (payload-driven).
     */
    public StubOverride whenBodyMatches(String jsonPath) {
        jsonPathMatchers.add(jsonPath);
        return this;
    }

    /**
     * Convenience matcher: respond only when the request body's {@code field}
     * equals {@code value} (e.g. {@code whenFieldEquals("name", "Ibuprofen")}).
     */
    public StubOverride whenFieldEquals(String field, Object value) {
        String literal = (value instanceof Number || value instanceof Boolean)
                ? String.valueOf(value)
                : "'" + value + "'";
        return whenBodyMatches("$[?(@." + field + " == " + literal + ")]");
    }

    /** HTTP status code to return (defaults to 200 for conditional overrides). */
    public StubOverride respondStatus(int status) {
        this.status = status;
        return this;
    }

    /** Explicit response body, taking precedence over any selected example. */
    public StubOverride withResponseBody(Object body) {
        this.responseBody = body;
        return this;
    }

    /** Commit the override in a single Admin API round-trip. */
    public void apply() {
        try {
            boolean conditional = !jsonPathMatchers.isEmpty() || status != null || responseBody != null;
            if (conditional) {
                int resolvedStatus = (status != null) ? status : 200;
                client.applyConditionalOverride(method, urlPattern, jsonPathMatchers,
                        exampleIndex, patches, resolvedStatus, responseBody);
            } else {
                client.applyOverride(method, urlPattern, exampleIndex, patches);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to apply override for " + method + " " + urlPattern, e);
        }
    }
}
