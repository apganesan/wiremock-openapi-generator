package com.agfa.orbis.common.mockengine;
import com.agfa.orbis.common.mockengine.api.*;
import com.agfa.orbis.common.mockengine.impl.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fluent builder for partially overriding a stub's response at runtime.
 *
 * <p>Only the properties you explicitly set via {@link #with} are changed;
 * all other fields retain the values generated from the OpenAPI spec example.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Change only 'status' — all other fields keep their OpenAPI example values
 * wiremock.forStub("GET", "/med/([^/]+)")
 *         .with("status", "discontinued")
 *         .apply();
 *
 * // Change multiple fields in a single round-trip
 * wiremock.forStub("GET", "/med/([^/]+)")
 *         .with("status", "active")
 *         .with("name", "Ibuprofen 400mg")
 *         .with("dosage.unit", "mg")   // nested dot-path
 *         .apply();
 * }</pre>
 *
 * <p>{@link #apply()} performs a single Admin API round-trip:
 * read existing stub → merge changes → re-register with priority 1.
 */
public final class StubOverride {

    private final StubClient client;
    private final String method;
    private final String urlPattern;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    StubOverride(StubClient client, String method, String urlPattern) {
        this.client     = client;
        this.method     = method;
        this.urlPattern = urlPattern;
    }

    /**
     * Set a single property by dot-notation path.
     *
     * @param jsonPath dot-separated path to the field (e.g. {@code "status"} or {@code "address.city"})
     * @param value    new value for that field
     * @return {@code this} for chaining
     */
    public StubOverride with(String jsonPath, Object value) {
        properties.put(jsonPath, value);
        return this;
    }

    /**
     * Apply all accumulated property changes in a single Admin API round-trip.
     * Read existing stub → merge → re-register.
     *
     * @throws IllegalStateException if the stub cannot be found or has no {@code jsonBody}
     * @throws UncheckedIOException  if the Admin API call fails
     */
    public void apply() {
        if (properties.isEmpty()) return;
        try {
            client.patchProperties(method, urlPattern, properties);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to patch stub " + method + " " + urlPattern, e);
        }
    }
}
