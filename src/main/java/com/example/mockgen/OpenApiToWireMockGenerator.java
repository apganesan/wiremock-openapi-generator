package com.example.mockgen;

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
import java.util.*;

/**
 * Parses an OpenAPI 3.0 specification and generates WireMock stub mapping JSON files.
 * <p>
 * Each example defined in an endpoint's response produces a separate stub file so that
 * the same URL pattern can be served with different payloads depending on the active
 * WireMock scenario state.
 */
public class OpenApiToWireMockGenerator {

    private static final Logger log = LoggerFactory.getLogger(OpenApiToWireMockGenerator.class);

    private static final String APPLICATION_JSON = "application/json";
    private static final int DEFAULT_STUB_PRIORITY = 5;
    private static final List<String> SUPPORTED_METHODS =
            Arrays.asList("get", "post", "put", "delete", "patch");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Main entry point: parse the OpenAPI file and write all generated stubs to {@code outputDir}.
     *
     * @param openApiFilePath path to the OpenAPI YAML or JSON file
     * @param outputDir       directory where stub JSON files will be written
     * @return number of stub files generated
     */
    public int generateStubs(String openApiFilePath, String outputDir) {
        log.info("Reading OpenAPI specification from: {}", openApiFilePath);

        // Validate that the spec file exists
        File specFile = new File(openApiFilePath);
        if (!specFile.exists()) {
            throw new IllegalArgumentException("OpenAPI file not found: " + openApiFilePath);
        }

        // Parse the spec with full resolution of $ref pointers
        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        parseOptions.setResolveFully(true);

        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(openApiFilePath, null, parseOptions);
        if (result.getMessages() != null && !result.getMessages().isEmpty()) {
            result.getMessages().forEach(msg -> log.warn("OpenAPI parse warning: {}", msg));
        }

        OpenAPI openAPI = result.getOpenAPI();
        if (openAPI == null) {
            throw new IllegalStateException("Failed to parse OpenAPI file: " + openApiFilePath
                    + " — " + result.getMessages());
        }

        if (openAPI.getPaths() == null || openAPI.getPaths().isEmpty()) {
            log.warn("No paths found in the OpenAPI specification.");
            return 0;
        }

        // Ensure output directory exists
        Path outputPath = Paths.get(outputDir);
        try {
            Files.createDirectories(outputPath);
        } catch (Exception e) {
            throw new RuntimeException("Cannot create output directory: " + outputDir, e);
        }

        log.info("Processing {} path(s) ...", openAPI.getPaths().size());
        int totalGenerated = 0;
        for (Map.Entry<String, PathItem> entry : openAPI.getPaths().entrySet()) {
            totalGenerated += processPath(entry.getKey(), entry.getValue(), outputPath);
        }

        log.info("✅  Generated {} stub file(s) in: {}", totalGenerated, outputDir);
        return totalGenerated;
    }

    /**
     * Process all HTTP operations defined on a single path.
     */
    private int processPath(String pathPattern, PathItem pathItem, Path outputPath) {
        int count = 0;
        for (String method : SUPPORTED_METHODS) {
            Operation operation = getOperation(pathItem, method);
            if (operation != null) {
                count += processOperation(method.toUpperCase(), pathPattern, operation, outputPath);
            }
        }
        return count;
    }

    /**
     * Reflectively retrieve the operation for a given HTTP method from a PathItem.
     */
    private Operation getOperation(PathItem pathItem, String method) {
        switch (method) {
            case "get":    return pathItem.getGet();
            case "post":   return pathItem.getPost();
            case "put":    return pathItem.getPut();
            case "delete": return pathItem.getDelete();
            case "patch":  return pathItem.getPatch();
            default:       return null;
        }
    }

    /**
     * Process a single HTTP method + path combination: find examples and write stub files.
     */
    private int processOperation(String method, String pathPattern, Operation operation, Path outputPath) {
        String operationId = operation.getOperationId();
        if (operationId == null || operationId.isEmpty()) {
            // Derive a fallback ID from method + path
            operationId = method.toLowerCase() + pathPattern.replaceAll("[^a-zA-Z0-9]", "_");
        }

        log.debug("Processing {} {} (operationId={})", method, pathPattern, operationId);

        // Convert path parameters to WireMock URL pattern (regex)
        String urlPathPattern = pathPattern.replaceAll("\\{[^}]+}", "([^/]+)");

        // Locate the primary successful response (first 2xx code)
        ApiResponse successfulResponse = getSuccessfulResponse(operation);
        if (successfulResponse == null) {
            log.warn("No 2xx response defined for {} {} — skipping", method, pathPattern);
            return 0;
        }

        // Determine HTTP status code
        int statusCode = getSuccessStatusCode(operation);

        // Handle operations with no response body (e.g., 204 No Content)
        Content content = successfulResponse.getContent();
        if (content == null || !content.containsKey(APPLICATION_JSON)) {
            generateStub(method, urlPathPattern, pathPattern, operationId,
                    "default", null, statusCode, outputPath);
            return 1;
        }

        MediaType mediaType = content.get(APPLICATION_JSON);
        Map<String, io.swagger.v3.oas.models.examples.Example> examples =
                mediaType.getExamples();

        if (examples == null || examples.isEmpty()) {
            // No named examples — try inline example
            Object inlineExample = mediaType.getExample();
            generateStub(method, urlPathPattern, pathPattern, operationId,
                    "default", inlineExample, statusCode, outputPath);
            return 1;
        }

        // One stub file per named example
        int count = 0;
        for (Map.Entry<String, io.swagger.v3.oas.models.examples.Example> exEntry : examples.entrySet()) {
            String exampleName = exEntry.getKey();
            Object exampleValue = exEntry.getValue().getValue();
            generateStub(method, urlPathPattern, pathPattern, operationId,
                    exampleName, exampleValue, statusCode, outputPath);
            count++;
        }
        return count;
    }

    /**
     * Write a single WireMock stub JSON file to disk.
     */
    private void generateStub(String method, String urlPathPattern, String originalPath,
                               String operationId, String exampleName, Object responseBody,
                               int statusCode, Path outputPath) {
        try {
            ObjectNode stub = objectMapper.createObjectNode();

            // ---- Request matcher ----
            ObjectNode request = objectMapper.createObjectNode();
            request.put("method", method);
            if (originalPath.contains("{")) {
                request.put("urlPathPattern", urlPathPattern);
            } else {
                request.put("urlPath", urlPathPattern);
            }
            stub.set("request", request);

            // ---- Response ----
            ObjectNode response = objectMapper.createObjectNode();
            response.put("status", statusCode);

            ObjectNode headers = objectMapper.createObjectNode();
            if (responseBody != null) {
                headers.put("Content-Type", APPLICATION_JSON);
                // Convert the example value to a proper JSON node
                response.set("jsonBody", objectMapper.valueToTree(responseBody));
            }
            response.set("headers", headers);
            stub.set("response", response);

            // ---- Scenario (enables switching between examples) ----
            stub.put("scenarioName", operationId);
            stub.put("requiredScenarioState", exampleName);
            stub.put("priority", DEFAULT_STUB_PRIORITY);

            // ---- Metadata ----
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("generatedFrom", "OpenAPI");
            metadata.put("operationId", operationId);
            metadata.put("exampleName", exampleName);
            metadata.put("generatedAt", System.currentTimeMillis());
            stub.set("metadata", metadata);

            String fileName = sanitizeFileName(operationId + "-" + exampleName) + ".json";
            Path filePath = outputPath.resolve(fileName);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), stub);
            log.info("  📄  {}", fileName);

        } catch (Exception e) {
            log.error("Failed to generate stub for {} {} example '{}': {}",
                    method, urlPathPattern, exampleName, e.getMessage(), e);
        }
    }

    /**
     * Return the first 2xx {@link ApiResponse} defined in the operation, or {@code null}.
     */
    private ApiResponse getSuccessfulResponse(Operation operation) {
        if (operation.getResponses() == null) {
            return null;
        }
        // Prefer explicit 200/201 first, then any 2xx
        for (String code : new String[]{"200", "201", "202", "204"}) {
            ApiResponse r = operation.getResponses().get(code);
            if (r != null) return r;
        }
        // Fall back to any key that starts with "2"
        return operation.getResponses().entrySet().stream()
                .filter(e -> e.getKey().startsWith("2"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * Return the HTTP status code for the first 2xx response, defaulting to 200.
     */
    private int getSuccessStatusCode(Operation operation) {
        if (operation.getResponses() == null) return 200;
        for (String code : new String[]{"200", "201", "202", "204"}) {
            if (operation.getResponses().containsKey(code)) {
                return Integer.parseInt(code);
            }
        }
        return operation.getResponses().keySet().stream()
                .filter(k -> k.startsWith("2"))
                .mapToInt(k -> { try { return Integer.parseInt(k); } catch (NumberFormatException e) { return 200; } })
                .findFirst()
                .orElse(200);
    }

    /**
     * Remove characters that are unsafe in filenames.
     */
    private String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "stub";
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    /** Convenience entry point for standalone execution. */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: OpenApiToWireMockGenerator <openapi-file> <output-dir>");
            System.exit(1);
        }
        int count = new OpenApiToWireMockGenerator().generateStubs(args[0], args[1]);
        System.out.println("Generated " + count + " stub(s).");
    }
}
