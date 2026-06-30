package com.agfa.orbis.common.mockengine.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Map;

/**
 * Client for querying and modifying stub mappings on a running mock server at runtime.
 *
 * <p>Responsibilities are split from server lifecycle ({@link MockServer}) in line with
 * the Interface Segregation Principle — test code that only needs to tweak stubs does not
 * need to depend on server start/stop concerns.
 */
public interface StubClient {

    /**
     * Find a registered stub by HTTP method and URL pattern.
     *
     * @return the stub JSON node, or {@code null} if not found
     */
    JsonNode findStub(String method, String urlPattern) throws IOException;

    /**
     * Register a high-priority stub that completely replaces the response body for the
     * given method + URL pattern.
     *
     * @return the stub ID assigned by the server
     */
    String override(String method, String urlPattern, Object responseBody) throws IOException;

    /**
     * Read the existing stub for the given endpoint, apply {@code properties} as a
     * shallow/deep merge into its {@code jsonBody}, and re-register the stub.
     *
     * <p>Fields <em>not</em> listed in {@code properties} retain the values originally
     * generated from the OpenAPI spec — only the specified fields change.
     *
     * @param properties map of dot-path → new value (e.g. {@code "status"} or {@code "address.city"})
     */
    void patchProperties(String method, String urlPattern,
                         Map<String, Object> properties) throws IOException;

    /** Update one item inside an array response body by its zero-based index. */
    void patchArrayItem(String method, String urlPattern, int index,
                        Map<String, Object> updates) throws IOException;

    /** Append a new item to an array response body. */
    void addArrayItem(String method, String urlPattern, Object item) throws IOException;

    /** Remove an item from an array response body by its zero-based index. */
    void removeArrayItem(String method, String urlPattern, int index) throws IOException;

    /** Transition a named WireMock scenario to the given state. */
    void switchScenario(String scenarioName, String state) throws IOException;

    /**
     * Register a sequence of responses for the same endpoint as a WireMock scenario.
     * Each call to the endpoint advances to the next response in the list.
     *
     * @param scenarioName unique name for the scenario
     * @param method       HTTP method
     * @param urlPattern   URL path or regex pattern
     * @param responses    ordered list of response bodies to cycle through
     */
    void registerSequence(String scenarioName, String method, String urlPattern,
                          java.util.List<?> responses) throws IOException;

    /**
     * Reset all stubs to the persisted state (i.e. what was generated from the spec).
     * Removes all runtime overrides.
     */
    void reset() throws IOException;
}
