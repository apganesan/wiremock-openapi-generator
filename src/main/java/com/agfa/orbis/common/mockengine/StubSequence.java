package com.agfa.orbis.common.mockengine;
import com.agfa.orbis.common.mockengine.api.*;
import com.agfa.orbis.common.mockengine.impl.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for registering <em>sequential</em> responses on the same endpoint.
 *
 * <p>Under the hood this uses WireMock Scenarios — each call to the endpoint advances
 * to the next response in the list. After the last response it cycles back to the first.
 *
 * <h2>When to use</h2>
 * <ul>
 *   <li><b>{@link StubOverride}</b> (via {@code wiremock.forStub(…)}) — when each
 *       <em>test</em> needs a different static response. {@code @BeforeEach} resets to
 *       spec defaults and the test overrides what it needs.</li>
 *   <li><b>{@link StubSequence}</b> (via {@code wiremock.forSequence(…)}) — when a
 *       <em>single test</em> needs the same endpoint to return different payloads on
 *       successive calls (e.g. polling, retry, state-change flows).</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // First call → active, second call → discontinued, then cycles back
 * wiremock.forSequence("med-status-flow", "GET", "/med/([^/]+)")
 *         .thenReturn(activeMedPayload)
 *         .thenReturn(discontinuedMedPayload)
 *         .register();
 *
 * // In the test:
 * assertThat(GET("/med/1")).isEqualTo(activeMedPayload);       // 1st call
 * assertThat(GET("/med/1")).isEqualTo(discontinuedMedPayload); // 2nd call
 * assertThat(GET("/med/1")).isEqualTo(activeMedPayload);       // 3rd call (cycles)
 * }</pre>
 */
public final class StubSequence {

    private final StubClient client;
    private final String scenarioName;
    private final String method;
    private final String urlPattern;
    private final List<Object> responses = new ArrayList<>();

    StubSequence(StubClient client, String scenarioName, String method, String urlPattern) {
        this.client       = client;
        this.scenarioName = scenarioName;
        this.method       = method;
        this.urlPattern   = urlPattern;
    }

    /**
     * Add the next response body in the sequence.
     *
     * @param responseBody any object serialisable to JSON
     * @return {@code this} for chaining
     */
    public StubSequence thenReturn(Object responseBody) {
        responses.add(responseBody);
        return this;
    }

    /**
     * Register all responses as a WireMock scenario.
     *
     * @throws IllegalStateException if no responses have been added
     * @throws UncheckedIOException  if the Admin API call fails
     */
    public void register() {
        if (responses.isEmpty()) {
            throw new IllegalStateException("Add at least one response with thenReturn()");
        }
        try {
            client.registerSequence(scenarioName, method, urlPattern, responses);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to register sequence '" + scenarioName + "'", e);
        }
    }
}
