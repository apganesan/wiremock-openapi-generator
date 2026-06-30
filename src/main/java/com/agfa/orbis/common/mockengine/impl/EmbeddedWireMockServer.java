package com.agfa.orbis.common.mockengine.impl;
import com.agfa.orbis.common.mockengine.api.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;

/**
 * {@link MockServer} implementation backed by an embedded WireMock server.
 */
public class EmbeddedWireMockServer implements MockServer {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedWireMockServer.class);

    private WireMockServer server;

    @Override
    public void start(String mappingsDir, int port) {
        if (server != null && server.isRunning()) {
            log.warn("Server already running on port {}. Skipping start.", server.port());
            return;
        }

        File mappingsDirFile = Paths.get(mappingsDir).toAbsolutePath().toFile();
        if (!mappingsDirFile.exists()) {
            boolean created = mappingsDirFile.mkdirs();
            if (!created) {
                throw new RuntimeException("Failed to create mappings directory: " + mappingsDirFile);
            }
        }

        // WireMock expects rootDir to be the *parent* of mappings/ so it can also find __files/
        File rootDir = mappingsDirFile.getParentFile();

        log.info("Starting WireMock server on port {} with root dir: {}",
                port == 0 ? "dynamic" : port, rootDir.getAbsolutePath());

        WireMockConfiguration config = port == 0
                ? WireMockConfiguration.options().dynamicPort()
                : WireMockConfiguration.options().port(port);

        config.withRootDirectory(rootDir.getAbsolutePath())
              .globalTemplating(true);

        server = new WireMockServer(config);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "wiremock-shutdown"));

        int actual = server.port();
        log.info("✅  WireMock server started — http://localhost:{} | admin: http://localhost:{}/__admin | stubs: {}",
                actual, actual, server.listAllStubMappings().getMappings().size());
    }

    @Override
    public void stop() {
        if (server != null && server.isRunning()) {
            log.info("Stopping WireMock server ...");
            server.stop();
        }
    }

    @Override
    public int getPort() {
        return server != null ? server.port() : -1;
    }

    @Override
    public boolean isRunning() {
        return server != null && server.isRunning();
    }
}
