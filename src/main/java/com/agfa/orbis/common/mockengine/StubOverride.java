package com.agfa.orbis.common.mockengine;

import com.agfa.orbis.common.mockengine.api.StubClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StubOverride {

    private final StubClient        client;
    private final String            method;
    private final String            urlPattern;
    private       Integer           exampleIndex = null;   // null = use currently active stub
    private final Map<String,Object> patches     = new LinkedHashMap<>();

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

    /** Commit the override in a single Admin API round-trip. */
    public void apply() {
        try {
            client.applyOverride(method, urlPattern, exampleIndex, patches);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to apply override for " + method + " " + urlPattern, e);
        }
    }
}
