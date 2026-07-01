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
     * Removes all runtime overrides and resets all scenario states to "Started".
     */
    void reset() throws IOException;

    /** Switch a WireMock scenario to a specific state (e.g. "404", "500"). */
    void switchScenarioState(String scenarioName, String state) throws IOException;
}
