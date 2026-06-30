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

# ── Test 1 – POST /med (first example: ibuprofen) ──
echo ""
echo "▶ Testing POST /med"
STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$HOST/med" \
    -H "Content-Type: application/json" \
    -d '{"id":1,"name":"Ibuprofen","type":"analgesic"}')
check "POST /med" "$STATUS" 200

echo "  Response body (truncated):"
curl -s -X POST "$HOST/med" -H "Content-Type: application/json" \
    -d '{"id":1,"name":"Ibuprofen","type":"analgesic"}' | head -c 300
echo ""

# ── Test 2 – GET /med/{id} (first example: ibuprofen) ──
echo ""
echo "▶ Testing GET /med/1"
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$HOST/med/1")
check "GET /med/1" "$STATUS" 200

echo "  Response body (truncated):"
curl -s "$HOST/med/1" | head -c 300
echo ""

# ── Test 3 – Runtime override for GET /med/999 ────
echo ""
echo "▶ Creating runtime override for GET /med/999"
OVERRIDE_BODY='{
  "request": {
    "method": "GET",
    "urlPath": "/med/999"
  },
  "response": {
    "status": 200,
    "headers": {"Content-Type": "application/json"},
    "jsonBody": {
      "id": 999,
      "name": "Override Drug",
      "type": "experimental",
      "properties": {
        "dosage": "10mg",
        "route": "intravenous",
        "frequency": "once daily"
      }
    }
  },
  "priority": 1
}'
OV_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$HOST/__admin/mappings" \
    -H "Content-Type: application/json" \
    -d "$OVERRIDE_BODY")
check "POST /__admin/mappings (override)" "$OV_STATUS" 201

# ── Test 4 – Verify override ──────────────────────
echo ""
echo "▶ Testing overridden GET /med/999"
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$HOST/med/999")
check "GET /med/999 (runtime override)" "$STATUS" 200

OVERRIDE_RESPONSE=$(curl -s "$HOST/med/999")
echo "  Response: $OVERRIDE_RESPONSE" | head -c 300
echo ""

# ── Test 5 – Admin API stats ──────────────────────
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
