package com.agfa.orbis.common.mockengine.impl;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.media.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class OpenApiExampleValidator {

    private OpenApiExampleValidator() {}

    /**
     * Validate {@code exampleNode} against {@code schema}.
     *
     * @param exampleNode the example JSON parsed into a Jackson node
     * @param schema      the OpenAPI response schema (resolved, no {@code $ref})
     * @return list of validation errors; empty if valid
     */
    public static List<String> validate(JsonNode exampleNode, Schema<?> schema) {
        List<String> errors = new ArrayList<>();
        validateNode(exampleNode, schema, "$", errors);
        return errors;
    }

    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void validateNode(JsonNode node, Schema<?> schema,
                                     String path, List<String> errors) {
        if (schema == null || node == null || node.isNull()) return;

        String schemaType = schema.getType();

        // --- Type check ---
        if (schemaType != null) {
            if (!typeMatches(node, schemaType)) {
                errors.add(path + ": expected type '" + schemaType
                        + "' but example has " + jsonTypeName(node));
                return; // no point checking properties if the type is wrong
            }
        }

        // --- Object: check required fields and recurse into properties ---
        if (node.isObject()) {
            Map<String, Schema> properties =
                    schema.getProperties() != null ? schema.getProperties() : Map.of();

            // Required fields must be present
            List<String> required = schema.getRequired();
            if (required != null) {
                for (String field : required) {
                    if (!node.has(field)) {
                        errors.add(path + ": required field '" + field + "' is missing");
                    }
                }
            }

            // Unknown fields (not in schema properties)
            Boolean additionalPropertiesAllowed = resolveAdditionalProperties(schema);
            if (Boolean.FALSE.equals(additionalPropertiesAllowed)) {
                node.fieldNames().forEachRemaining(field -> {
                    if (!properties.containsKey(field)) {
                        errors.add(path + ": field '" + field
                                + "' is not declared in the schema (additionalProperties: false)");
                    }
                });
            }

            // Recurse into declared properties
            properties.forEach((field, propSchema) -> {
                if (node.has(field)) {
                    validateNode(node.get(field), propSchema, path + "." + field, errors);
                }
            });
        }

        // --- Array: validate items schema ---
        if (node.isArray() && schema.getItems() != null) {
            Schema<?> itemSchema = schema.getItems();
            for (int i = 0; i < node.size(); i++) {
                validateNode(node.get(i), itemSchema, path + "[" + i + "]", errors);
            }
        }
    }

    private static boolean typeMatches(JsonNode node, String schemaType) {
        switch (schemaType) {
            case "object":  return node.isObject();
            case "array":   return node.isArray();
            case "string":  return node.isTextual();
            case "integer": return node.isIntegralNumber();
            case "number":  return node.isNumber();
            case "boolean": return node.isBoolean();
            default:        return true; // unknown type — don't fail
        }
    }

    private static String jsonTypeName(JsonNode node) {
        if (node.isObject())          return "object";
        if (node.isArray())           return "array";
        if (node.isTextual())         return "string";
        if (node.isIntegralNumber())  return "integer";
        if (node.isFloatingPointNumber()) return "number";
        if (node.isBoolean())         return "boolean";
        if (node.isNull())            return "null";
        return "unknown";
    }

    private static Boolean resolveAdditionalProperties(Schema<?> schema) {
        Object ap = schema.getAdditionalProperties();
        if (ap instanceof Boolean) return (Boolean) ap;
        return null; // not set → additional properties allowed
    }
}
