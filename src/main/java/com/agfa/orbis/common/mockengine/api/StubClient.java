package com.agfa.orbis.common.mockengine.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Client for querying and modifying stub mappings on a running mock server at runtime.
 */
public interface StubClient {

    /** Find a registered stub by HTTP method and URL pattern. */
    JsonNode findStub(String method, String urlPattern) throws IOException;


    void applyOverride(String method, String urlPattern,
                       Integer exampleIndex, Map<String, Object> patches) throws IOException;

    /**
     * Register a conditional override stub that only responds when the request body
     * matches all the given JSONPath expressions. Enables payload-driven responses,
     * e.g. one payload → 200, a different payload → 500.
     *
     * @param jsonPathMatchers WireMock {@code matchesJsonPath} expressions (all must match)
     * @param exampleIndex     optional base example for the response body (ignored if {@code rawBody} given)
     * @param patches          field overrides applied on top of the base body
     * @param status           HTTP status code to return
     * @param rawBody          explicit response body (takes precedence over {@code exampleIndex})
     */
    void applyConditionalOverride(String method, String urlPattern,
                                  List<String> jsonPathMatchers, Integer exampleIndex,
                                  Map<String, Object> patches, int status,
                                  Object rawBody) throws IOException;

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
