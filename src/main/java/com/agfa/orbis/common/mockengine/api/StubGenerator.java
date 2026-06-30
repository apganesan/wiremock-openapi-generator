package com.agfa.orbis.common.mockengine.api;

/**
 * Parses an API specification and emits WireMock stub mapping files.
 *
 * <p>Implement this interface to support additional spec formats (e.g. RAML, gRPC proto).
 */
public interface StubGenerator {

    /**
     * Generate WireMock stub JSON files from the given specification.
     *
     * @param specFile  path to the spec file (YAML or JSON)
     * @param outputDir directory where stub JSON files are written
     * @return number of stub files generated
     */
    int generate(String specFile, String outputDir);
}
