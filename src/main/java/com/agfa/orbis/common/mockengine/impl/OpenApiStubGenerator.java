package com.agfa.orbis.common.mockengine.impl;

import com.agfa.orbis.common.mockengine.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenApiStubGenerator implements StubGenerator {

    private static final Logger log = LoggerFactory.getLogger(OpenApiStubGenerator.class);

    private static final String APPLICATION_JSON = "application/json";
    private static final int    DEFAULT_PRIORITY  = 5;
    private static final List<String> HTTP_METHODS =
            Arrays.asList("get", "post", "put", "delete", "patch");

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public int generate(String specFile, String outputDir) {
        log.info("Reading OpenAPI specification from: {}", specFile);

        if (!new File(specFile).exists()) {
            throw new IllegalArgumentException("OpenAPI file not found: " + specFile);
        }

        ParseOptions opts = new ParseOptions();
        opts.setResolve(true);
        opts.setResolveFully(true);

        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(specFile, null, opts);
        if (result.getMessages() != null) {
            result.getMessages().forEach(m -> log.warn("OpenAPI parse warning: {}", m));
        }

        OpenAPI openAPI = result.getOpenAPI();
        if (openAPI == null) {
            throw new IllegalStateException("Failed to parse: " + specFile + " — " + result.getMessages());
        }
        if (openAPI.getPaths() == null || openAPI.getPaths().isEmpty()) {
            log.warn("No paths found in the specification.");
            return 0;
        }

        Path out = Paths.get(outputDir);
        try { Files.createDirectories(out); }
        catch (Exception e) { throw new RuntimeException("Cannot create output directory: " + outputDir, e); }

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
            if (op != null) count += processOperation(method.toUpperCase(), path, op, out);
        }
        return count;
    }

    private int processOperation(String method, String path, Operation op, Path out) {
        String operationId = op.getOperationId();
        if (operationId == null || operationId.isEmpty()) {
            operationId = method.toLowerCase() + path.replaceAll("[^a-zA-Z0-9]", "_");
        }

        String urlPattern = path.replaceAll("\\{[^}]+}", "([^/]+)");

        if (op.getResponses() == null || op.getResponses().isEmpty()) {
            log.warn("No responses defined for {} {} — skipping", method, path);
            return 0;
        }

        // Require at least one 2xx response; error-only specs are skipped
        boolean hasSuccess = op.getResponses().keySet().stream()
                .anyMatch(k -> k.startsWith("2") && !k.equals("default"));
        if (!hasSuccess) {
            log.warn("No 2xx response for {} {} — skipping", method, path);
            return 0;
        }

        int count = 0;
        boolean firstSuccess = true;

        for (Map.Entry<String, ApiResponse> entry : op.getResponses().entrySet()) {
            String statusStr = entry.getKey();
            if ("default".equals(statusStr)) continue;

            int statusCode;
            try { statusCode = Integer.parseInt(statusStr); }
            catch (NumberFormatException e) { continue; }

            ApiResponse response = entry.getValue();

            // First 2xx → default "Started" state; subsequent or error responses → state = statusCode
            boolean isFirstSuccess = firstSuccess && statusCode >= 200 && statusCode < 300;
            if (isFirstSuccess) firstSuccess = false;

            String scenarioState = isFirstSuccess ? "Started" : statusStr;

            Content content = response.getContent();
            if (content == null || !content.containsKey(APPLICATION_JSON)) {
                writeStub(method, urlPattern, path, operationId,
                        null, null, statusCode, scenarioState, out);
                count++;
                continue;
            }

            MediaType mediaType = content.get(APPLICATION_JSON);
            Schema<?> schema   = mediaType.getSchema();
            Map<String, Example> examples = mediaType.getExamples();

            Object    defaultBody = null;
            ArrayNode allExamples = null;   // only populated for the success stub

            if (examples == null || examples.isEmpty()) {
                defaultBody = mediaType.getExample();
                if (schema != null && defaultBody != null) {
                    validateExample(defaultBody, schema, "default", operationId);
                }
                if (isFirstSuccess) {
                    allExamples = buildExamplesMetadata(null, null, defaultBody);
                }
            } else {
                if (schema != null) {
                    final String opId = operationId;
                    examples.forEach((name, ex) -> validateExample(ex.getValue(), schema, name, opId));
                }
                Map.Entry<String, Example> first = examples.entrySet().iterator().next();
                defaultBody = first.getValue().getValue();
                if (isFirstSuccess) {
                    allExamples = buildExamplesMetadata(examples, first.getKey(), null);
                }
            }

            writeStub(method, urlPattern, path, operationId,
                    defaultBody, allExamples, statusCode, scenarioState, out);
            count++;
        }

        return count;
    }

    /**
     * Write a single stub file. All stubs for the same operationId share a WireMock scenario:
     * the success stub uses state "Started" (default); error stubs use the HTTP status code as state.
     */
    private void writeStub(String method, String urlPattern, String originalPath,
                            String operationId, Object defaultBody,
                            ArrayNode allExamples, int statusCode, String scenarioState, Path out) {
        try {
            ObjectNode stub = mapper.createObjectNode();

            // Request matcher
            ObjectNode request = mapper.createObjectNode();
            request.put("method", method);
            request.put(originalPath.contains("{") ? "urlPathPattern" : "urlPath", urlPattern);
            stub.set("request", request);

            // Response
            ObjectNode response = mapper.createObjectNode();
            response.put("status", statusCode);
            ObjectNode headers = mapper.createObjectNode();
            if (defaultBody != null) {
                headers.put("Content-Type", APPLICATION_JSON);
                response.set("jsonBody", mapper.valueToTree(defaultBody));
            }
            response.set("headers", headers);
            stub.set("response", response);

            stub.put("priority", DEFAULT_PRIORITY);

            // Scenario — all stubs for same operationId share a scenario name.
            // Success stub: state="Started" (WireMock default initial state).
            // Error stubs:  state="{statusCode}" (e.g. "404", "500").
            stub.put("scenarioName", operationId);
            stub.put("requiredScenarioState", scenarioState);

            // Metadata — examples stored here for selectExample() at test time (success stub only)
            ObjectNode meta = mapper.createObjectNode();
            meta.put("generatedFrom", "OpenAPI");
            meta.put("operationId", operationId);
            if (allExamples != null) {
                meta.set("examples", allExamples);
            }
            stub.set("metadata", meta);

            // One file per status code: getMedicationById-200.json, getMedicationById-404.json, …
            String fileName = sanitize(operationId) + "-" + statusCode + ".json";
            mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve(fileName).toFile(), stub);
            log.info("  📄  {} (state: {}, examples: {})",
                    fileName, scenarioState,
                    allExamples != null ? allExamples.size() : "-");

        } catch (Exception e) {
            log.error("Failed to write stub for {} {}: {}", method, urlPattern, e.getMessage(), e);
        }
    }

    /**
     * Build a {@code [{name, body}, ...]} array for metadata.examples.
     * {@code defaultFirst} is the name that should be listed first.
     */
    private ArrayNode buildExamplesMetadata(Map<String, Example> examples,
                                             String defaultFirst, Object inlineBody) {
        ArrayNode arr = mapper.createArrayNode();
        if (inlineBody != null) {
            ObjectNode ex = mapper.createObjectNode();
            ex.put("name", "default");
            ex.set("body", mapper.valueToTree(inlineBody));
            arr.add(ex);
            return arr;
        }
        if (examples == null) return arr;

        // Put default first, then the rest
        Map<String, Example> ordered = new LinkedHashMap<>();
        if (defaultFirst != null && examples.containsKey(defaultFirst)) {
            ordered.put(defaultFirst, examples.get(defaultFirst));
        }
        examples.forEach((k, v) -> ordered.putIfAbsent(k, v));

        ordered.forEach((name, ex) -> {
            ObjectNode node = mapper.createObjectNode();
            node.put("name", name);
            node.set("body", mapper.valueToTree(ex.getValue()));
            arr.add(node);
        });
        return arr;
    }

    // -------------------------------------------------------------------------
    // Schema validation — warns on mismatch so generation always completes
    // -------------------------------------------------------------------------

    private void validateExample(Object exampleValue, Schema<?> schema,
                                  String exampleName, String operationId) {
        if (exampleValue == null || schema == null) return;
        try {
            JsonNode node = mapper.valueToTree(exampleValue);
            List<String> errors = OpenApiExampleValidator.validate(node, schema);
            if (!errors.isEmpty()) {
                log.warn("⚠️  Schema violations in example '{}' (operationId={}):", exampleName, operationId);
                errors.forEach(e -> log.warn("     • {}", e));
            }
        } catch (Exception e) {
            log.warn("Could not validate example '{}': {}", exampleName, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
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

    private String sanitize(String name) {
        if (name == null || name.isEmpty()) return "stub";
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
