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

/**
 * {@link StubGenerator} implementation that parses an OpenAPI 3.0 specification and
 * generates WireMock stub mapping files.
 *
 * <h2>Stub strategy — no scenario cycling</h2>
 * <p>One stub file is written per endpoint. When multiple examples exist in the spec:
 * <ul>
 *   <li>The <b>first example</b> becomes the default {@code jsonBody} returned for
 *       any request that doesn't have a runtime override.</li>
 *   <li><b>All examples</b> (name + body) are stored in {@code metadata.examples} so
 *       that {@link WireMockAdminClient#selectExample} can directly override the
 *       response body at test time — no scenario state machine involved.</li>
 * </ul>
 *
 * <h2>Schema validation</h2>
 * <p>Each example is validated against the OpenAPI response schema before the stub is
 * written. Missing required fields or wrong types emit a {@code WARN} log so the
 * generator continues but the mismatch is visible.
 */
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

        ApiResponse successResponse = firstSuccessResponse(op);
        if (successResponse == null) {
            log.warn("No 2xx response for {} {} — skipping", method, path);
            return 0;
        }

        int statusCode = successStatusCode(op);
        Content content = successResponse.getContent();

        if (content == null || !content.containsKey(APPLICATION_JSON)) {
            writeStub(method, urlPattern, path, operationId, null, null, statusCode, out);
            return 1;
        }

        MediaType mediaType = content.get(APPLICATION_JSON);
        Schema<?> schema = mediaType.getSchema();
        Map<String, Example> examples = mediaType.getExamples();

        if (examples == null || examples.isEmpty()) {
            Object inlineExample = mediaType.getExample();
            if (schema != null && inlineExample != null) {
                validateExample(inlineExample, schema, "default", operationId);
            }
            writeStub(method, urlPattern, path, operationId,
                    inlineExample, buildExamplesMetadata(null, null, inlineExample), statusCode, out);
            return 1;
        }

        // Validate every example against the schema
        if (schema != null) {
            final String opId = operationId;
            examples.forEach((name, ex) ->
                    validateExample(ex.getValue(), schema, name, opId));
        }

        // First example = default response body
        Map.Entry<String, Example> first = examples.entrySet().iterator().next();
        Object defaultBody = first.getValue().getValue();

        // All examples embedded in metadata for selectExample()
        ArrayNode allExamples = buildExamplesMetadata(examples, first.getKey(), null);

        writeStub(method, urlPattern, path, operationId, defaultBody, allExamples, statusCode, out);
        return 1;
    }

    /**
     * Write a single stub file. One file per endpoint — no scenario fields.
     * All examples stored in {@code metadata.examples} for runtime selection.
     */
    private void writeStub(String method, String urlPattern, String originalPath,
                            String operationId, Object defaultBody,
                            ArrayNode allExamples, int statusCode, Path out) {
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

            // Metadata — examples stored here for selectExample() at test time
            ObjectNode meta = mapper.createObjectNode();
            meta.put("generatedFrom", "OpenAPI");
            meta.put("operationId", operationId);
            if (allExamples != null) {
                meta.set("examples", allExamples);
            }
            stub.set("metadata", meta);

            String fileName = sanitize(operationId) + ".json";
            mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve(fileName).toFile(), stub);
            log.info("  📄  {} (default: {}, total examples: {})",
                    fileName,
                    allExamples != null && allExamples.size() > 0
                            ? allExamples.get(0).path("name").asText() : "inline",
                    allExamples != null ? allExamples.size() : 1);

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

    private ApiResponse firstSuccessResponse(Operation op) {
        if (op.getResponses() == null) return null;
        for (String code : new String[]{"200", "201", "202", "204"}) {
            ApiResponse r = op.getResponses().get(code);
            if (r != null) return r;
        }
        return op.getResponses().entrySet().stream()
                .filter(e -> e.getKey().startsWith("2"))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private int successStatusCode(Operation op) {
        if (op.getResponses() == null) return 200;
        for (String code : new String[]{"200", "201", "202", "204"}) {
            if (op.getResponses().containsKey(code)) return Integer.parseInt(code);
        }
        return 200;
    }

    private String sanitize(String name) {
        if (name == null || name.isEmpty()) return "stub";
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
