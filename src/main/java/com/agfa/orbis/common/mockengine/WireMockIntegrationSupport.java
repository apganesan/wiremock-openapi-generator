package com.agfa.orbis.common.mockengine;
import com.agfa.orbis.common.mockengine.api.*;
import com.agfa.orbis.common.mockengine.impl.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WireMockIntegrationSupport implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WireMockIntegrationSupport.class);

    private final String        openApiFile;
    private final int           requestedPort;
    private final String        explicitMappingsDir;
    private final StubGenerator stubGenerator;
    private final MockServer    mockServer;

    /** Temp root created when no explicit mappings dir is given. */
    private Path               tempRoot;
    private WireMockAdminClient adminClient;

    private WireMockIntegrationSupport(Builder b) {
        this.openApiFile          = b.openApiFile;
        this.requestedPort        = b.port;
        this.explicitMappingsDir  = b.mappingsDir;
        this.stubGenerator        = b.stubGenerator;
        this.mockServer           = b.mockServer;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Generate stubs from the spec, start the server, and connect the admin client.
     *
     * @return {@code this} for fluent / try-with-resources usage
     */
    public WireMockIntegrationSupport start() {
        String mappingsDir = resolveMappingsDir();

        log.info("Starting WireMock support — spec={}, port={}",
                openApiFile, requestedPort == 0 ? "dynamic" : requestedPort);

        stubGenerator.generate(openApiFile, mappingsDir);
        mockServer.start(mappingsDir, requestedPort);

        adminClient = new WireMockAdminClient("localhost", mockServer.getPort());
        log.info("WireMock support ready — baseUrl={}", getBaseUrl());
        return this;
    }

    /** Stop the server and clean up any temporary directories. */
    public void stop() {
        mockServer.stop();
        deleteTempRoot();
        log.info("WireMock support stopped.");
    }

    /** Implements {@link AutoCloseable} for try-with-resources. */
    @Override
    public void close() {
        stop();
    }

    /**
     * Reset all stubs to the state generated from the OpenAPI spec.
     * Call this in {@code @BeforeEach} to ensure each test starts from a clean slate.
     */
    public void resetStubs() {
        ensureStarted();
        try {
            adminClient.reset();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to reset stubs", e);
        }
    }

    // -------------------------------------------------------------------------
    // Partial property override — the primary test-time API
    // -------------------------------------------------------------------------

    public StubOverride forStub(String method, String urlPattern) {
        ensureStarted();
        return new StubOverride(adminClient, method, urlPattern);
    }

    /**
     * Switch a scenario to an error state so the next request returns that HTTP status.
     * Call {@link #resetStubs()} to return to the default success response.
     *
     * @param operationId  the OpenAPI {@code operationId} (used as WireMock scenario name)
     * @param statusCode   the error HTTP status code to activate (e.g. 404, 500)
     */
    public void switchToError(String operationId, int statusCode) {
        ensureStarted();
        try {
            adminClient.switchScenarioState(operationId, String.valueOf(statusCode));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to switch scenario state", e);
        }
    }
    // -------------------------------------------------------------------------

    /** @return actual TCP port the server is listening on */
    public int getPort() {
        ensureStarted();
        return mockServer.getPort();
    }

    /** @return {@code http://localhost:{port}} — base URL for all stub requests */
    public String getBaseUrl() {
        return "http://localhost:" + getPort();
    }

    /** @return the {@link StubClient} for advanced/direct Admin API operations */
    public StubClient getStubClient() {
        ensureStarted();
        return adminClient;
    }

    /** @return {@code true} if the server is currently running */
    public boolean isRunning() {
        return mockServer.isRunning();
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String        openApiFile;
        private int           port        = 0;      // 0 = random free port
        private String        mappingsDir = null;   // null = temp dir
        private StubGenerator stubGenerator;
        private MockServer    mockServer;

        private Builder() {}

        /** Path to the OpenAPI YAML/JSON specification file (required). */
        public Builder openApiFile(String openApiFile) {
            this.openApiFile = openApiFile;
            return this;
        }

        /**
         * TCP port for the WireMock server.
         * Default {@code 0} picks a random free port — ideal for parallel test execution.
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * Directory for generated stub files.
         * Omit to use a temporary directory that is cleaned up on {@link #stop()}.
         */
        public Builder mappingsDir(String mappingsDir) {
            this.mappingsDir = mappingsDir;
            return this;
        }

        /**
         * Override the stub generator (default: {@link OpenApiStubGenerator}).
         * Implement {@link StubGenerator} to support other spec formats.
         */
        public Builder stubGenerator(StubGenerator stubGenerator) {
            this.stubGenerator = stubGenerator;
            return this;
        }

        /**
         * Override the mock server (default: {@link EmbeddedWireMockServer}).
         * Implement {@link MockServer} to use a different mock server library.
         */
        public Builder mockServer(MockServer mockServer) {
            this.mockServer = mockServer;
            return this;
        }

        public WireMockIntegrationSupport build() {
            if (openApiFile == null || openApiFile.isEmpty()) {
                throw new IllegalStateException("openApiFile must be specified");
            }
            if (stubGenerator == null) stubGenerator = new OpenApiStubGenerator();
            if (mockServer    == null) mockServer    = new EmbeddedWireMockServer();
            return new WireMockIntegrationSupport(this);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String resolveMappingsDir() {
        if (explicitMappingsDir != null) return explicitMappingsDir;
        try {
            tempRoot = Files.createTempDirectory("wiremock-it-");
            Path mappings = tempRoot.resolve("mappings");
            Files.createDirectories(mappings);
            return mappings.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create temp mappings dir", e);
        }
    }

    private void deleteTempRoot() {
        if (tempRoot == null) return;
        try {
            deleteRecursively(tempRoot);
        } catch (IOException e) {
            log.warn("Could not delete temp dir {}: {}", tempRoot, e.getMessage());
        } finally {
            tempRoot = null;
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : (Iterable<Path>) entries::iterator) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.delete(path);
    }

    private void ensureStarted() {
        if (!mockServer.isRunning()) {
            throw new IllegalStateException(
                    "WireMockIntegrationSupport not started — call start() first.");
        }
    }
}
