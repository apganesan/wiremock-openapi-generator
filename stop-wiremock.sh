#!/usr/bin/env bash
# stop-wiremock.sh
# Stop the WireMock Docker container and clean up.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "═══════════════════════════════════════════════"
echo "  Stopping WireMock Mock Server"
echo "═══════════════════════════════════════════════"
echo ""

docker compose down --remove-orphans

echo ""
echo "✅  WireMock stopped."
