package com.agfa.orbis.common.mockengine;

import com.agfa.orbis.common.mockengine.api.MockServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/**
 * {@link MockServer} implementation that runs WireMock inside a Docker container
 * using the Docker CLI — recommended for integration tests.
 *
 * <p>Stubs are bind-mounted from the host mappings directory into the container, so
 * no image rebuild is needed when specs change.
 *
 * <p>Usage:
 * <pre>{@code
 * WireMockIntegrationSupport.builder()
 *         .openApiFile("src/test/resources/openapi.yaml")
 *         .mockServer(new DockerWireMockServer())
 *         .build()
 *         .start();
 * }</pre>
 */
public class DockerWireMockServer implements MockServer {

    private static final Logger log = LoggerFactory.getLogger(DockerWireMockServer.class);

    private static final String IMAGE        = "wiremock/wiremock:3.3.1";
    private static final int    HEALTH_RETRIES = 30;
    private static final int    HEALTH_INTERVAL_MS = 1_000;

    private String containerName;
    private int    mappedPort = -1;
    private boolean running   = false;

    @Override
    public void start(String mappingsDir, int port) {
        File dir = new File(mappingsDir);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalArgumentException("Mappings directory does not exist: " + mappingsDir);
        }

        containerName = "wiremock-test-" + UUID.randomUUID().toString().substring(0, 8);
        // Mount the parent of mappings/ as the WireMock root so --root-dir /home/wiremock
        // resolves both mappings/ and __files/ — consistent with EmbeddedWireMockServer.
        String rootDir = dir.getParentFile().getAbsolutePath();

        log.info("Starting WireMock Docker container '{}' (image={}) with root dir: {}",
                containerName, IMAGE, rootDir);

        exec("docker", "run", "-d",
                "--name", containerName,
                "-p", "0:8080",
                "-v", rootDir + ":/home/wiremock:ro",
                IMAGE,
                "--global-response-templating", "--verbose",
                "--root-dir", "/home/wiremock");

        mappedPort = resolvePort();
        waitForHealth();
        running = true;

        log.info("WireMock Docker container ready — http://localhost:{} | admin: http://localhost:{}/__admin",
                mappedPort, mappedPort);
    }

    @Override
    public void stop() {
        if (!running || containerName == null) return;
        log.info("Stopping WireMock Docker container '{}'...", containerName);
        try {
            exec("docker", "stop", containerName);
            exec("docker", "rm",   containerName);
        } finally {
            running = false;
        }
    }

    @Override
    public int getPort() {
        return mappedPort;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private int resolvePort() {
        // "docker port <name> 8080" outputs "0.0.0.0:PORT" or ":::PORT"
        String out = execOutput("docker", "port", containerName, "8080").trim();
        String[] parts = out.split(":");
        try {
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Could not parse mapped port from: " + out, e);
        }
    }

    private void waitForHealth() {
        log.info("Waiting for WireMock to become healthy on port {}...", mappedPort);
        for (int i = 0; i < HEALTH_RETRIES; i++) {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                        new URL("http://localhost:" + mappedPort + "/__admin/health").openConnection();
                conn.setConnectTimeout(1_000);
                conn.setReadTimeout(2_000);
                if (conn.getResponseCode() == 200) {
                    log.info("WireMock is healthy after {}s", i);
                    return;
                }
            } catch (Exception ignored) { }
            sleep(HEALTH_INTERVAL_MS);
        }
        throw new RuntimeException("WireMock container did not become healthy within "
                + HEALTH_RETRIES + "s — check: docker logs " + containerName);
    }

    private void exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            int exit = p.waitFor();
            if (exit != 0) {
                String out = new String(p.getInputStream().readAllBytes());
                throw new RuntimeException("Command failed (exit " + exit + "): "
                        + String.join(" ", cmd) + "\n" + out);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute: " + String.join(" ", cmd), e);
        }
    }

    private String execOutput(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append("\n");
                p.waitFor();
                return sb.toString();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute: " + String.join(" ", cmd), e);
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
