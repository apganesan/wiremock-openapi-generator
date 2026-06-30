package com.agfa.orbis.common.mockengine.impl;
import com.agfa.orbis.common.mockengine.api.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@link StubGenerator} implementation that parses an OpenAPI 3.0 specification and
 * generates WireMock stub mapping JSON files — one file per named example.
 *
 * <p>Multiple examples on the same endpoint are wired into a WireMock scenario so that
 * repeated requests cycle through each example in turn.
 */
public class OpenApiStubGenerator implements StubGenerator {

    private static final Logger log = LoggerFactory.getLogger(OpenApiStubGenerator.class);

    private static final String APPLICATION_JSON = "application/json";
    private static final int    DEFAULT_PRIORITY  = 5;
    private static final List<String> HTTP_METHODS =
            Arrays.asList("get", "post", "put", "delete", "patch");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public int generate(String specFile, String outputDir) {
        log.info("Reading OpenAPI specification from: {}", specFile);

        File spec = new File(specFile);
        if (!spec.exists()) {
            throw new IllegalArgumentException("OpenAPI file not found: " + specFile);
        }

        ParseOptions opts = new ParseOptions();
        opts.setResolve(true);
        opts.setResolveFully(true);

        SwaggerParseResult result =
                new OpenAPIV3Parser().readLocation(specFile, null, opts);

        if (result.getMessages() != null) {
            result.getMessages().forEach(m -> log.warn("OpenAPI parse warning: {}", m));
        }

        OpenAPI openAPI = result.getOpenAPI();
        if (openAPI == null) {
            throw new IllegalStateException(
                    "Failed to parse OpenAPI file: " + specFile + " — " + result.getMessages());
        }

        if (openAPI.getPaths() == null || openAPI.getPaths().isEmpty()) {
            log.warn("No paths found in the OpenAPI specification.");
            return 0;
        }

        Path out = Paths.get(outputDir);
        try {
            Files.createDirectories(out);
        } catch (Exception e) {
            throw new RuntimeException("Cannot create output directory: " + outputDir, e);
        }

        log.info("Processing {} path(s) ...", openAPI.getPaths().size());
        int total = 0;
        for (Map.Entry<String, PathItem> entry : openAPI.getPaths().entrySet()) {
            total += processPath(entry.getKey(), entry.getValue(), out);
        }

        log.info("✅  Generated {} stub file(s) in: {}", total, outputDir);
        return total;
    }

    // -------------------------------------------------------------------------

    private int processPath(String path, PathItem item, Path out) {
        int count = 0;
        for (String method : HTTP_METHODS) {
            Operation op = operation(item, method);
            if (op != null) {
                count += processOperation(method.toUpperCase(), path, op, out);
            }
        }
        return count;
    }

    private int processOperation(String method, String path, Operation op, Path out) {
        String operationId = op.getOperationId();
        if (operationId == null || operationId.isEmpty()) {
            operationId = method.toLowerCase() + path.replaceAll("[^a-zA-Z0-9]", "_");
        }

        String urlPattern = path.replaceAll("\\{[^}]+}", "([^/]+)");

        ApiResponse successResponse = firstSuccessResponse(op);
        if (successResponse == null) {
            log.warn("No 2xx response for {} {} — skipping", method, path);
            return 0;
        }

        int statusCode = successStatusCode(op);
        Content content = successResponse.getContent();

        if (content == null || !content.containsKey(APPLICATION_JSON)) {
            writeStub(method, urlPattern, path, operationId,
                    "default", null, statusCode, out, Collections.emptyList(), 0);
            return 1;
        }

        MediaType mediaType = content.get(APPLICATION_JSON);
        Map<String, io.swagger.v3.oas.models.examples.Example> examples = mediaType.getExamples();

        if (examples == null || examples.isEmpty()) {
            writeStub(method, urlPattern, path, operationId,
                    "default", mediaType.getExample(), statusCode, out, Collections.emptyList(), 0);
            return 1;
        }

        List<String> names = new ArrayList<>(examples.keySet());
        for (int i = 0; i < names.size(); i++) {
            writeStub(method, urlPattern, path, operationId,
                    names.get(i), examples.get(names.get(i)).getValue(),
                    statusCode, out, names, i);
        }
        return names.size();
    }

    private void writeStub(String method, String urlPattern, String originalPath,
                            String operationId, String exampleName, Object body,
                            int status, Path out, List<String> allExamples, int index) {
        try {
            ObjectNode stub = objectMapper.createObjectNode();

            ObjectNode request = objectMapper.createObjectNode();
            request.put("method", method);
            if (originalPath.contains("{")) {
                request.put("urlPathPattern", urlPattern);
            } else {
                request.put("urlPath", urlPattern);
            }
            stub.set("request", request);

            ObjectNode response = objectMapper.createObjectNode();
            response.put("status", status);
            ObjectNode headers = objectMapper.createObjectNode();
            if (body != null) {
                headers.put("Content-Type", APPLICATION_JSON);
                response.set("jsonBody", objectMapper.valueToTree(body));
            }
            response.set("headers", headers);
            stub.set("response", response);

            if (allExamples.size() > 1) {
                boolean first = index == 0;
                boolean last  = index == allExamples.size() - 1;
                stub.put("scenarioName", operationId);
                stub.put("requiredScenarioState",
                        first ? "Started" : allExamples.get(index - 1));
                stub.put("newScenarioState",
                        last  ? "Started" : allExamples.get(index));
            }
            stub.put("priority", DEFAULT_PRIORITY);

            ObjectNode meta = objectMapper.createObjectNode();
            meta.put("generatedFrom", "OpenAPI");
            meta.put("operationId", operationId);
            meta.put("exampleName", exampleName);
            stub.set("metadata", meta);

            String fileName = sanitize(operationId + "-" + exampleName) + ".json";
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(out.resolve(fileName).toFile(), stub);
            log.info("  📄  {}", fileName);

        } catch (Exception e) {
            log.error("Failed to write stub for {} {} example '{}': {}",
                    method, urlPattern, exampleName, e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------

    private Operation operation(PathItem item, String method) {
        switch (method) {
            case "get":    return item.getGet();
            case "post":   return item.getPost();
            case "put":    return item.getPut();
            case "delete": return item.getDelete();
            case "patch":  return item.getPatch();
            default:       return null;
        }
    }

    private ApiResponse firstSuccessResponse(Operation op) {
        if (op.getResponses() == null) return null;
        for (String code : new String[]{"200", "201", "202", "204"}) {
            ApiResponse r = op.getResponses().get(code);
            if (r != null) return r;
        }
        return op.getResponses().entrySet().stream()
                .filter(e -> e.getKey().startsWith("2"))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private int successStatusCode(Operation op) {
        if (op.getResponses() == null) return 200;
        for (String code : new String[]{"200", "201", "202", "204"}) {
            if (op.getResponses().containsKey(code)) return Integer.parseInt(code);
        }
        return op.getResponses().keySet().stream()
                .filter(k -> k.startsWith("2"))
                .mapToInt(k -> { try { return Integer.parseInt(k); } catch (NumberFormatException e) { return 200; } })
                .findFirst().orElse(200);
    }

    private String sanitize(String name) {
        if (name == null || name.isEmpty()) return "stub";
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
