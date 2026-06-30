package com.agfa.orbis.common.mockengine;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;

/**
 * Manages the lifecycle of an embedded WireMock server.
 * <p>
 * The server loads stub mappings from a configurable directory on the filesystem and
 * supports WireMock's response-templating extension so that dynamic values can be
 * injected into responses at request time.
 */
public class WireMockServerRunner {

    private static final Logger log = LoggerFactory.getLogger(WireMockServerRunner.class);

    private static final int DEFAULT_PORT = 8080;

    private WireMockServer server;

    /**
     * Start the embedded WireMock server.
     *
     * @param mappingsDir absolute or relative path to the directory that contains stub JSON files
     * @param port        TCP port the server should listen on
     */
    public void start(String mappingsDir, int port) {
        if (server != null && server.isRunning()) {
            log.warn("WireMock server is already running on port {}. Skipping start.", server.port());
            return;
        }

        File mappingsDirFile = Paths.get(mappingsDir).toAbsolutePath().toFile();
        if (!mappingsDirFile.exists()) {
            log.warn("Mappings directory does not exist, creating it: {}", mappingsDirFile);
            boolean created = mappingsDirFile.mkdirs();
            if (!created) {
                throw new RuntimeException("Failed to create mappings directory: " + mappingsDirFile);
            }
        }

        // The root dir is the parent of 'mappings/' so WireMock can find both
        // mappings/ and __files/ subdirectories automatically.
        File rootDir = mappingsDirFile.getParentFile();

        log.info("Starting WireMock server on port {} with root dir: {}", port, rootDir.getAbsolutePath());

        WireMockConfiguration config = WireMockConfiguration.options()
                .port(port)
                .withRootDirectory(rootDir.getAbsolutePath())
                .extensions("com.github.tomakehurst.wiremock.extension.responsetemplating.ResponseTemplateTransformer");

        server = new WireMockServer(config);
        server.start();

        // Register a JVM shutdown hook so Ctrl+C stops the server cleanly
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "wiremock-shutdown-hook"));

        int mappingCount = server.listAllStubMappings().getMappings().size();
        log.info("✅  WireMock server started.");
        log.info("    API endpoints : http://localhost:{}", port);
        log.info("    Admin API     : http://localhost:{}/__admin", port);
        log.info("    Stub mappings : {} loaded", mappingCount);
    }

    /**
     * Gracefully stop the embedded server.
     */
    public void stop() {
        if (server != null && server.isRunning()) {
            log.info("Stopping WireMock server ...");
            server.stop();
            log.info("WireMock server stopped.");
        }
    }

    /** @return {@code true} if the server is currently running */
    public boolean isRunning() {
        return server != null && server.isRunning();
    }

    /** @return the underlying {@link WireMockServer} instance, or {@code null} if not started */
    public WireMockServer getServer() {
        return server;
    }

    /**
     * Standalone entry point.
     * <p>
     * Usage: {@code WireMockServerRunner [mappings-dir] [port]}
     * Defaults: mappings-dir = {@code wiremock/mappings}, port = 8080
     */
    public static void main(String[] args) throws InterruptedException {
        String mappingsDir = args.length > 0 ? args[0] : "wiremock/mappings";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;

        WireMockServerRunner runner = new WireMockServerRunner();
        runner.start(mappingsDir, port);

        log.info("Server is running. Press Ctrl+C to stop.");
        // Keep the main thread alive until the shutdown hook fires
        Thread.currentThread().join();
    }
}
