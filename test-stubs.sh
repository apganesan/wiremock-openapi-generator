#!/usr/bin/env bash
# test-stubs.sh
# Smoke-test the running WireMock server endpoints and demonstrate a runtime override.

set -euo pipefail

HOST="http://localhost:8080"
PASS=0
FAIL=0

echo "═══════════════════════════════════════════════"
echo "  WireMock Stub Tests"
echo "═══════════════════════════════════════════════"

# ── Helper ─────────────────────────────────────────
check() {
    local LABEL="$1"
    local STATUS="$2"
    local EXPECTED="$3"
    if [ "$STATUS" -eq "$EXPECTED" ]; then
        echo "  ✅  $LABEL (HTTP $STATUS)"
        PASS=$((PASS + 1))
    else
        echo "  ❌  $LABEL (expected HTTP $EXPECTED, got HTTP $STATUS)"
        FAIL=$((FAIL + 1))
    fi
}

# ── Test 1 – GET /api/users ────────────────────────
echo ""
echo "▶ Testing GET /api/users"
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$HOST/api/users")
check "GET /api/users" "$STATUS" 200

echo "  Response body (truncated):"
curl -s "$HOST/api/users" | head -c 300
echo ""

# ── Test 2 – GET /api/users/{id} ──────────────────
echo ""
echo "▶ Testing GET /api/users/1"
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$HOST/api/users/1")
check "GET /api/users/1" "$STATUS" 200

curl -s "$HOST/api/users/1" | head -c 300
echo ""

# ── Test 3 – POST /api/users ──────────────────────
echo ""
echo "▶ Testing POST /api/users"
STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$HOST/api/users" \
    -H "Content-Type: application/json" \
    -d '{"name":"Test User","email":"test@example.com"}')
check "POST /api/users" "$STATUS" 201

# ── Test 4 – Runtime override for /api/users/999 ──
echo ""
echo "▶ Creating runtime override for GET /api/users/999"
OVERRIDE_BODY='{
  "request": {
    "method": "GET",
    "urlPath": "/api/users/999"
  },
  "response": {
    "status": 200,
    "headers": {"Content-Type": "application/json"},
    "jsonBody": {
      "id": 999,
      "name": "Runtime Override User",
      "email": "override@example.com",
      "role": "admin",
      "status": "active"
    }
  },
  "priority": 1
}'
OV_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$HOST/__admin/mappings" \
    -H "Content-Type: application/json" \
    -d "$OVERRIDE_BODY")
check "POST /__admin/mappings (override)" "$OV_STATUS" 201

# ── Test 5 – Verify override ──────────────────────
echo ""
echo "▶ Testing overridden GET /api/users/999"
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$HOST/api/users/999")
check "GET /api/users/999 (runtime override)" "$STATUS" 200

OVERRIDE_RESPONSE=$(curl -s "$HOST/api/users/999")
echo "  Response: $OVERRIDE_RESPONSE" | head -c 300
echo ""

# ── Test 6 – Admin API stats ──────────────────────
echo ""
echo "▶ Checking Admin API stats"
STATS_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$HOST/__admin/mappings")
check "GET /__admin/mappings" "$STATS_STATUS" 200

TOTAL=$(curl -s "$HOST/__admin/mappings" | \
    python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('meta',{}).get('total',len(d.get('mappings',[]))))" 2>/dev/null || echo "?")
echo "  Total stubs registered: $TOTAL"

# ── Summary ────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════"
echo "  Results: $PASS passed, $FAIL failed"
echo "═══════════════════════════════════════════════"

if [ $FAIL -gt 0 ]; then
    exit 1
fi
