#!/usr/bin/env bash
# start-wiremock.sh
# Generate stubs (if missing) and start WireMock via Docker Compose.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

MAPPINGS_DIR="wiremock/mappings"
WIREMOCK_PORT=8080
MAX_WAIT=60   # seconds to wait for health check

echo "═══════════════════════════════════════════════"
echo "  Starting WireMock Mock Server"
echo "═══════════════════════════════════════════════"

# ── Check Docker ───────────────────────────────────
if ! docker info >/dev/null 2>&1; then
    echo "❌  Docker is not running. Please start Docker and try again."
    exit 1
fi
echo "✅  Docker is running."

# ── Generate stubs if mappings directory is empty ──
if [ -z "$(ls -A "$MAPPINGS_DIR" 2>/dev/null | grep '\.json$')" ]; then
    echo ""
    echo "⚠️   No stub files found. Running stub generator first..."
    bash generate-stubs.sh
fi

# ── Stop any existing containers ───────────────────
echo ""
echo "▶ Stopping any existing WireMock containers..."
docker-compose down --remove-orphans 2>/dev/null || true

# ── Start docker-compose ───────────────────────────
echo ""
echo "▶ Starting WireMock (port $WIREMOCK_PORT) ..."
docker-compose up -d

# ── Wait for health check ──────────────────────────
echo ""
echo "▶ Waiting for WireMock to become healthy..."
ELAPSED=0
while [ $ELAPSED -lt $MAX_WAIT ]; do
    STATUS=$(docker inspect --format='{{.State.Health.Status}}' wiremock-mock-server 2>/dev/null || echo "unknown")
    if [ "$STATUS" = "healthy" ]; then
        break
    fi
    printf "   [%ds] status=%s\r" "$ELAPSED" "$STATUS"
    sleep 2
    ELAPSED=$((ELAPSED + 2))
done

echo ""
STATUS=$(docker inspect --format='{{.State.Health.Status}}' wiremock-mock-server 2>/dev/null || echo "unknown")
if [ "$STATUS" != "healthy" ]; then
    echo "❌  WireMock did not become healthy within ${MAX_WAIT}s (status=$STATUS)."
    echo "    Check logs with: docker-compose logs wiremock"
    exit 1
fi

# ── Summary ────────────────────────────────────────
MAPPING_COUNT=$(curl -s "http://localhost:${WIREMOCK_PORT}/__admin/mappings" | \
    python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('meta',{}).get('total',len(d.get('mappings',[]))))" 2>/dev/null || echo "?")

echo "✅  WireMock is healthy and ready."
echo ""
echo "   🌐  API         : http://localhost:${WIREMOCK_PORT}"
echo "   🔧  Admin API   : http://localhost:${WIREMOCK_PORT}/__admin"
echo "   📄  Mappings    : ${MAPPING_COUNT} loaded"
echo ""
echo "Run './test-stubs.sh' to test the endpoints."
echo "Run './stop-wiremock.sh' to stop the server."
