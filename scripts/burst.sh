#!/usr/bin/env bash
#
# One-command burst script for the wallet service. Reproduces the live probes:
#   1. concurrent get-or-create -> exactly one wallet
#   2. idempotent retry storm    -> exactly one debit/credit, identical responses
#   3. conservation under load   -> total unchanged, no negative balances
#   4. reversal double-fire      -> exactly one refund (R3 follow-up)
#
# Usage:
#   BASE_URL=https://your-app.onrender.com ADMIN_TOKEN=... ./scripts/burst.sh
#   ./scripts/burst.sh            # defaults to http://localhost:8080
#
# Requires: bash, curl, python3 (stdlib only).

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-dev-admin-token}"
STAMP="$(date +%s)-$$"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass=0
fail=0
ok()  { echo "  PASS  $1"; pass=$((pass + 1)); }
bad() { echo "  FAIL  $1"; fail=$((fail + 1)); }
hdr() { echo; echo "=== $1 ==="; }

jget() { python3 -c "import sys,json;print(json.load(sys.stdin).get('$1',''))"; }

api() { # METHOD PATH TOKEN [BODY]
  local method="$1" path="$2" token="$3" body="${4:-}"
  if [[ -n "$body" ]]; then
    curl -sS -X "$method" "$BASE_URL$path" \
      -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$body"
  else
    curl -sS -X "$method" "$BASE_URL$path" -H "Authorization: Bearer $token"
  fi
}

# Fire N identical requests concurrently, one response per file (no stdout interleaving).
# fire N tag METHOD PATH TOKEN [BODY]
fire() {
  local n="$1" tag="$2" method="$3" path="$4" token="$5" body="${6:-}"
  local dir="$WORK/$tag"
  mkdir -p "$dir"
  local i
  for ((i = 1; i <= n; i++)); do
    if [[ -n "$body" ]]; then
      curl -sS -o "$dir/$i.body" -w '%{http_code}\n' -X "$method" "$BASE_URL$path" \
        -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
        -d "$body" > "$dir/$i.code" &
    else
      curl -sS -o "$dir/$i.body" -w '%{http_code}\n' -X "$method" "$BASE_URL$path" \
        -H "Authorization: Bearer $token" > "$dir/$i.code" &
    fi
  done
  wait
  for f in "$dir"/*.body; do printf '\n' >> "$f"; done
  echo "$dir"
}

new_wallet() { api POST /wallets "$1" "" | jget id; }
balance()    { api GET "/wallets/$2" "$1" | jget balance_paise; }
mint() { api POST /admin/credit "$ADMIN_TOKEN" "{\"wallet_id\":\"$1\",\"amount_paise\":$2}" >/dev/null; }

echo "target: $BASE_URL"
if api GET /health "" "" | grep -q '"status":"UP"'; then ok "service reachable"; else bad "service unreachable"; exit 1; fi

# ---------------------------------------------------------------------------
hdr "1. Concurrent get-or-create (50x POST /wallets, fresh user)"
DIR="$(fire 50 wallets POST /wallets "race-$STAMP")"
DISTINCT="$(cat "$DIR"/*.body | python3 -c 'import sys,json;print(len({json.loads(l)["id"] for l in sys.stdin if l.strip()}))')"
RESP="$(ls "$DIR"/*.body | grep -c .)"
[[ "$RESP" == "50" && "$DISTINCT" == "1" ]] \
  && ok "50 concurrent creates -> 1 distinct wallet" \
  || bad "expected 50 responses / 1 wallet, got $RESP / $DISTINCT"

# ---------------------------------------------------------------------------
hdr "2. Idempotent retry storm (30x same transfer, same key)"
A="$(new_wallet "alice-$STAMP")"; B="$(new_wallet "bob-$STAMP")"
mint "$A" 100000
KEY="storm-$STAMP"
DIR="$(fire 30 storm POST /transfers "alice-$STAMP" \
  "{\"from\":\"$A\",\"to\":\"$B\",\"amount_paise\":40000,\"idempotency_key\":\"$KEY\"}")"
T_DISTINCT="$(cat "$DIR"/*.body | python3 -c 'import sys,json;print(len({json.loads(l)["id"] for l in sys.stdin if l.strip()}))')"
CODES="$(cat "$DIR"/*.code | sort -u | tr '\n' ' ')"
BAL_A="$(balance "alice-$STAMP" "$A")"; BAL_B="$(balance "bob-$STAMP" "$B")"
[[ "$T_DISTINCT" == "1" ]] && ok "all 30 responses carry one transfer id" || bad "distinct transfer ids: $T_DISTINCT"
[[ "$CODES" == "200 " ]] && ok "all 30 responses are 200" || bad "status codes seen: $CODES"
[[ "$BAL_A" == "60000" && "$BAL_B" == "40000" ]] \
  && ok "exactly one debit/credit (A=$BAL_A B=$BAL_B)" \
  || bad "balances wrong (A=$BAL_A B=$BAL_B, want 60000/40000)"

CONFLICT="$(curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$BASE_URL/transfers" \
  -H "Authorization: Bearer alice-$STAMP" -H 'Content-Type: application/json' \
  -d "{\"from\":\"$A\",\"to\":\"$B\",\"amount_paise\":999,\"idempotency_key\":\"$KEY\"}")"
[[ "$CONFLICT" == "409" ]] && ok "same key + different body -> 409" || bad "same key + different body -> $CONFLICT"

# ---------------------------------------------------------------------------
hdr "3. Conservation under contention (240 concurrent transfers, 4 wallets)"
W0="$(new_wallet "conv-$STAMP-0")"; W1="$(new_wallet "conv-$STAMP-1")"
W2="$(new_wallet "conv-$STAMP-2")"; W3="$(new_wallet "conv-$STAMP-3")"
for w in "$W0" "$W1" "$W2" "$W3"; do mint "$w" 100000; done
TOTAL_BEFORE=400000
WA=("$W0" "$W1" "$W2" "$W3")
mkdir -p "$WORK/conv"
for ((i = 1; i <= 240; i++)); do
  f=$((RANDOM % 4)); t=$((RANDOM % 4)); while [[ $t -eq $f ]]; do t=$((RANDOM % 4)); done
  if [[ $((RANDOM % 2)) -eq 0 ]]; then amt=$((RANDOM % 5000 + 1)); else amt=$((RANDOM % 200000 + 90000)); fi
  curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer conv-$STAMP-0" -H 'Content-Type: application/json' \
    -d "{\"from\":\"${WA[$f]}\",\"to\":\"${WA[$t]}\",\"amount_paise\":$amt,\"idempotency_key\":\"conv-$STAMP-$i\"}" \
    >> "$WORK/conv/codes" &
  if (( i % 40 == 0 )); then wait; fi
done
wait
TOTAL_AFTER=0; NEG=0
for i in 0 1 2 3; do
  b="$(balance "conv-$STAMP-$i" "$(eval echo \$W$i)")"
  TOTAL_AFTER=$((TOTAL_AFTER + b))
  (( b < 0 )) && NEG=$((NEG + 1))
done
BAD_CODES="$(grep -vE '^(200|422)$' "$WORK/conv/codes" | sort -u | tr '\n' ' ' || true)"
[[ "$TOTAL_AFTER" == "$TOTAL_BEFORE" ]] && ok "money conserved ($TOTAL_BEFORE -> $TOTAL_AFTER)" || bad "conservation broken -> $TOTAL_AFTER"
[[ "$NEG" == "0" ]] && ok "no negative balances" || bad "$NEG wallet(s) negative"
[[ -z "$BAD_CODES" ]] && ok "every transfer resolved 200/422 (no 5xx)" || bad "unexpected status codes: $BAD_CODES"
echo "  ($(grep -c '^422$' "$WORK/conv/codes" || true) declined for insufficient funds)"

# ---------------------------------------------------------------------------
hdr "4. Reversal double-fire (R3: 10x concurrent reverse, same key)"
RA="$(new_wallet "rev-a-$STAMP")"; RB="$(new_wallet "rev-b-$STAMP")"
mint "$RA" 50000
TID="$(api POST /transfers "rev-a-$STAMP" \
  "{\"from\":\"$RA\",\"to\":\"$RB\",\"amount_paise\":20000,\"idempotency_key\":\"rev-src-$STAMP\"}" | jget id)"
DIR="$(fire 10 reverse POST "/transfers/$TID/reverse" "rev-a-$STAMP" "{\"idempotency_key\":\"rev-$STAMP\"}")"
RBAL_A="$(balance "rev-a-$STAMP" "$RA")"; RBAL_B="$(balance "rev-b-$STAMP" "$RB")"
[[ "$RBAL_A" == "50000" && "$RBAL_B" == "0" ]] \
  && ok "reversed exactly once (A=$RBAL_A B=$RBAL_B)" \
  || bad "reversal not exactly-once (A=$RBAL_A B=$RBAL_B)"
AGAIN="$(curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$BASE_URL/transfers/$TID/reverse" \
  -H "Authorization: Bearer rev-a-$STAMP" -H 'Content-Type: application/json' \
  -d "{\"idempotency_key\":\"rev-different-$STAMP\"}")"
[[ "$AGAIN" == "409" ]] && ok "re-reverse with a new key -> 409" || bad "re-reverse -> $AGAIN"

# ---------------------------------------------------------------------------
echo
echo "=== $pass passed, $fail failed ==="
exit $(( fail > 0 ? 1 : 0 ))
