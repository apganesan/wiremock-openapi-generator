package com.agfa.orbis.common.mockengine.api;

/**
 * Manages the lifecycle of an embedded mock HTTP server.
 *
 * <p>Implement this interface to swap WireMock for another mock server (e.g. MockServer).
 */
public interface MockServer {

    /**
     * Start the server, loading stubs from {@code mappingsDir}.
     *
     * @param mappingsDir path to the {@code mappings/} directory containing stub JSON files
     * @param port        TCP port to bind; use {@code 0} for a random free port
     */
    void start(String mappingsDir, int port);

    /** Gracefully stop the server. */
    void stop();

    /**
     * @return actual TCP port the server is listening on, or {@code -1} if not started.
     *         Use this after {@link #start} when port {@code 0} (dynamic) was requested.
     */
    int getPort();

    /** @return {@code true} if the server is currently running */
    boolean isRunning();
}
