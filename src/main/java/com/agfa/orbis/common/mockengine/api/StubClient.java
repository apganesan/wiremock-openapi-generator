package com.agfa.orbis.common.mockengine.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Map;

/**
 * Client for querying and modifying stub mappings on a running mock server at runtime.
 */
public interface StubClient {

    /** Find a registered stub by HTTP method and URL pattern. */
    JsonNode findStub(String method, String urlPattern) throws IOException;

    /**
     * Apply an override for the given endpoint.
     *
     * <p>This is the single method backing {@link com.agfa.orbis.common.mockengine.StubOverride}:
     *
     * <ul>
     *   <li>If {@code exampleIndex} is non-null: load that example body from
     *       {@code metadata.examples} in the generated stub, apply {@code patches} on top,
     *       and register the result as a priority-1 override.</li>
     *   <li>If {@code exampleIndex} is {@code null}: read whatever the server is currently
     *       serving (highest-priority matching stub), apply {@code patches} on top,
     *       and re-register.</li>
     *   <li>If {@code patches} is empty and {@code exampleIndex} is set: the example body
     *       is used as-is (pure example selection).</li>
     * </ul>
     *
     * @param exampleIndex 0-based index into {@code metadata.examples}, or {@code null}
     *                     to patch the currently active stub body
     * @param patches      dot-path → new value overrides; may be empty
     */
    void applyOverride(String method, String urlPattern,
                       Integer exampleIndex, Map<String, Object> patches) throws IOException;

    /** Update one item inside an array response body by its zero-based index. */
    void patchArrayItem(String method, String urlPattern, int index,
                        Map<String, Object> updates) throws IOException;

    /** Append a new item to an array response body. */
    void addArrayItem(String method, String urlPattern, Object item) throws IOException;

    /** Remove an item from an array response body by its zero-based index. */
    void removeArrayItem(String method, String urlPattern, int index) throws IOException;

    /**
     * Reset all stubs to the state generated from the spec.
     * Removes all runtime overrides.
     */
    void reset() throws IOException;
}
