#!/usr/bin/env bash
# generate-stubs.sh
# Build the project and run the OpenAPI → WireMock stub generator.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

OPENAPI_FILE="src/main/resources/sample-openapi.yaml"
MAPPINGS_DIR="wiremock/mappings"
JAR="target/wiremock-openapi-generator-1.0.0-jar-with-dependencies.jar"

echo "═══════════════════════════════════════════════"
echo "  WireMock OpenAPI Stub Generator"
echo "═══════════════════════════════════════════════"

# ── Build ──────────────────────────────────────────
echo ""
echo "▶ Building project with Maven..."
if ! mvn clean package -q; then
    echo "❌  Maven build failed. Check output above for errors."
    exit 1
fi
echo "✅  Build successful."

# ── Verify JAR ─────────────────────────────────────
if [ ! -f "$JAR" ]; then
    echo "❌  Expected JAR not found: $JAR"
    exit 1
fi

# ── Generate stubs ─────────────────────────────────
echo ""
echo "▶ Generating WireMock stubs from: $OPENAPI_FILE"
mkdir -p "$MAPPINGS_DIR"

java -cp "$JAR" \
    com.example.mockgen.OpenApiToWireMockGenerator \
    "$OPENAPI_FILE" \
    "$MAPPINGS_DIR"

# ── Report ─────────────────────────────────────────
echo ""
echo "▶ Generated stub files:"
if ls "$MAPPINGS_DIR"/*.json 1>/dev/null 2>&1; then
    ls -1 "$MAPPINGS_DIR"/*.json | while read -r f; do
        echo "   📄  $(basename "$f")"
    done
    TOTAL=$(ls "$MAPPINGS_DIR"/*.json | wc -l | tr -d ' ')
    echo ""
    echo "✅  Total: $TOTAL stub file(s) in $MAPPINGS_DIR/"
else
    echo "⚠️   No stub files found in $MAPPINGS_DIR/ — check OpenAPI file for examples."
fi
