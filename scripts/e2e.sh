#!/usr/bin/env bash
# e2e.sh — Slate v2 end-to-end QA (backend only; emulator steps run separately)
#
# 1. starts a wake-capture listener on :3199 (heredoc'd bun script in the temp dir)
#    and boots the backend on :3124 with a temp SQLite DB, SLATE_API_KEY=test-key,
#    SLATE_WEBHOOK_URL=http://localhost:3199/capture, SLATE_WEBHOOK_SECRET=e2e-secret
#    (assumes `cd backend && bun install` has been run)
# 2. pushes every schema/golden/*.json — all must succeed except
#    unknown-element.json, which must 400 with a non-empty actionable hint
# 3. device roundtrip: /api/device/slates list + If-None-Match → 304
# 4. posts an answer+check interaction batch, polls responses, verifies fields,
#    acks with beforeId, verifies the ack
# 5. webhook wake: the listener must have seen exactly one POST per accepted
#    interaction (replayed seqs must NOT wake), body type=="interactions",
#    matching slateId, a valid HMAC-SHA256 signature — and a tampered
#    signature must fail validation; then the listener is stopped
# 6. agent loop: fresh question slate → device answer → agent poll →
#    ack with the poll cursor → re-push of the identical slate (stable contentHash)
# 7. skill smoke: skill/slate/scripts/slate.sh push/get/list/responses/ack/
#    clear-responses against the booted backend + `validate` rejecting bad JSON
# 8. prints a PASS/FAIL summary; exits nonzero on any failure
#
# Usage: scripts/e2e.sh [--port N] [--base-url URL] [--api-key KEY] [--keep] [--help]
#   --port N      port for the booted backend (default 3124)
#   --base-url U  test an already-running server instead of booting one
#                 (skips the webhook-wake section — no capture listener is attached)
#   --api-key K   shared key (default test-key)
#   --keep        do not kill the booted server / delete the temp DB (debugging)
# Requires: curl, jq, bun. Also binds localhost:3199 for the wake listener
# (only when booting the backend).
set -euo pipefail

PORT=3124
API_KEY="test-key"
WAKE_PORT=3199
WAKE_SECRET="e2e-secret"
KEEP=0
SKIP_BOOT=0
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/../backend"
GOLDEN_DIR="$SCRIPT_DIR/../schema/golden"
SKILL_CLI="$SCRIPT_DIR/../skill/slate/scripts/slate.sh"
LOG=/tmp/slate-e2e-server.log

usage() {
  cat <<'USAGE'
Usage: scripts/e2e.sh [--port N] [--base-url URL] [--api-key KEY] [--keep] [--help]

  --port N      port for the booted backend (default 3124)
  --base-url U  test an already-running server instead of booting one
                (skips the webhook-wake section — no capture listener is attached)
  --api-key K   shared key (default test-key)
  --keep        do not kill the booted server / delete the temp DB (debugging)
  --help        this text

When booting the backend, a wake-capture listener is also started on :3199
(the backend is pointed at it via SLATE_WEBHOOK_URL) and stopped afterwards.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --port)     PORT="$2"; shift 2 ;;
    --base-url) BASE_URL="$2"; SKIP_BOOT=1; shift 2 ;;
    --api-key)  API_KEY="$2"; shift 2 ;;
    --keep)     KEEP=1; shift ;;
    -h|--help)  usage; exit 0 ;;
    *)          echo "e2e: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if ! command -v curl >/dev/null 2>&1; then echo "e2e: curl is required" >&2; exit 2; fi
if ! command -v jq   >/dev/null 2>&1; then echo "e2e: jq is required"   >&2; exit 2; fi
if ! command -v bun  >/dev/null 2>&1; then echo "e2e: bun is required (backend + wake listener)" >&2; exit 2; fi

BASE_URL="${BASE_URL:-http://localhost:${PORT}}"
AUTH="Authorization: Bearer ${API_KEY}"
CT="Content-Type: application/json"

# ---- state ----
PASS=0; FAIL=0
SERVER_PID=""
LISTENER_PID=""
TMPDB_DIR=""
WORK_DIR=""
WORK_DIR_MINE=0

cleanup() {
  # The capture listener is always torn down (it also self-exits after its TTL).
  if [[ -n "$LISTENER_PID" ]]; then
    kill "$LISTENER_PID" 2>/dev/null || true
    wait "$LISTENER_PID" 2>/dev/null || true
  fi
  if [[ "$KEEP" != "1" ]]; then
    if [[ -n "$SERVER_PID" ]]; then
      kill "$SERVER_PID" 2>/dev/null || true
      wait "$SERVER_PID" 2>/dev/null || true
    fi
    if [[ -n "$TMPDB_DIR" && -d "$TMPDB_DIR" ]]; then rm -rf "$TMPDB_DIR"; fi
    if [[ "$WORK_DIR_MINE" == "1" && -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then rm -rf "$WORK_DIR"; fi
  elif [[ -n "$SERVER_PID" ]]; then
    echo "e2e(--keep): server left running, pid $SERVER_PID, db ${TMPDB_DIR:-n/a}"
  fi
}
trap cleanup EXIT

# ---- assertion helpers (never abort; tally and continue) ----
pass() { PASS=$((PASS+1)); printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; }
assert_eq() { # desc got want
  if [[ "$2" == "$3" ]]; then pass "$1"; else fail "$1 — expected [$3], got [$2]"; fi
}
assert_contains() { # desc haystack needle
  if [[ "$2" == *"$3"* ]]; then pass "$1"; else fail "$1 — missing [$3] in [${2:0:200}]"; fi
}

# ---- HTTP helper: sets STATUS + BODY ----
STATUS=0; BODY=""
req() { # method path [data-file] [raw-body]
  local method=$1 path=$2
  local args=( -sS -X "$method" -H "$AUTH" )
  if [[ $# -gt 2 && -n "${3:-}" ]]; then args+=( -H "$CT" --data-binary "@${3}" ); fi
  if [[ $# -gt 3 && -n "${4:-}" ]]; then args+=( -H "$CT" --data-binary "${4}" ); fi
  local tmp out
  tmp=$(mktemp)
  if ! out=$(curl "${args[@]}" -o "$tmp" -w '%{http_code}' "$BASE_URL$path" 2>/dev/null); then
    out="000"; BODY=""
    fail "request $method $path — curl could not reach ${BASE_URL}"
  else
    BODY=$(cat "$tmp")
  fi
  STATUS=$out
  rm -f "$tmp"
}

section() { printf '\n== %s ==\n' "$1"; }

# =========================================================================
section "backend boot (port ${PORT}, temp DB, SLATE_API_KEY=${API_KEY}, wake listener :${WAKE_PORT})"
if [[ "$SKIP_BOOT" != "1" ]]; then
  if [[ ! -d "$BACKEND_DIR" ]]; then
    echo "e2e: backend dir not found: $BACKEND_DIR" >&2; exit 1
  fi
  if lsof -nP -i ":${PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
    printf '  \033[31mFAIL\033[0m port %s is already in use — another slate backend (or a leaked e2e run) is listening.\n' "$PORT"
    printf '         kill it or pass --port N. Refusing to test against a server we did not boot.\n'
    exit 1
  fi
  if lsof -nP -i ":${WAKE_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
    printf '  \033[31mFAIL\033[0m wake-capture port %s is already in use — kill the stale listener first.\n' "$WAKE_PORT"
    exit 1
  fi
  TMPDB_DIR="$(mktemp -d /tmp/slate-e2e.XXXXXX)"
  WORK_DIR="$TMPDB_DIR"

  # Wake-capture listener: records every POST /capture (body + X-Slate-Signature)
  # as a JSON line to $WAKE_OUT and self-exits after WAKE_TTL_MS.
  cat >"$TMPDB_DIR/listener.mjs" <<'MJS'
// e2e wake-capture listener — records POST bodies + X-Slate-Signature headers,
// one JSON line per request, then exits after WAKE_TTL_MS.
import { appendFileSync } from "node:fs";
const OUT = process.env.WAKE_OUT ?? "";
const PORT = Number(process.env.WAKE_PORT ?? "3199");
const TTL_MS = Number(process.env.WAKE_TTL_MS ?? "90000");
Bun.serve({
  port: PORT,
  async fetch(req) {
    const url = new URL(req.url);
    if (req.method !== "POST" || url.pathname !== "/capture") {
      return new Response("e2e wake capture: POST /capture only\n", { status: 404 });
    }
    const body = await req.text();
    appendFileSync(OUT, JSON.stringify({ body, sig: req.headers.get("x-slate-signature") ?? "" }) + "\n");
    return new Response("captured\n");
  },
});
setTimeout(() => process.exit(0), TTL_MS);
MJS
  WAKE_OUT="$TMPDB_DIR/wakes.jsonl" WAKE_PORT="$WAKE_PORT" WAKE_TTL_MS=90000 \
    bun "$TMPDB_DIR/listener.mjs" >"$TMPDB_DIR/listener.log" 2>&1 &
  LISTENER_PID=$!
  LISTEN_UP=0
  for _ in $(seq 1 40); do
    if curl -s -o /dev/null "http://localhost:${WAKE_PORT}/" 2>/dev/null; then LISTEN_UP=1; break; fi
    if ! kill -0 "$LISTENER_PID" 2>/dev/null; then break; fi
    sleep 0.25
  done
  if [[ "$LISTEN_UP" != "1" ]]; then
    fail "wake-capture listener did not start on :${WAKE_PORT} — log follows"
    tail -20 "$TMPDB_DIR/listener.log" || true
    exit 1
  fi
  pass "wake-capture listener up on :${WAKE_PORT} (pid ${LISTENER_PID})"

  # exec so SERVER_PID is the bun process itself — cleanup's kill actually stops it.
  ( cd "$BACKEND_DIR" \
    && export SLATE_PORT="$PORT" SLATE_DB_PATH="$TMPDB_DIR/e2e.db" SLATE_API_KEY="$API_KEY" \
            SLATE_LOG_LEVEL=warn \
            SLATE_WEBHOOK_URL="http://localhost:${WAKE_PORT}/capture" \
            SLATE_WEBHOOK_SECRET="$WAKE_SECRET" \
    && exec bun run src/index.ts ) >"$LOG" 2>&1 &
  SERVER_PID=$!
  UP=0
  for _ in $(seq 1 60); do
    CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/health" 2>/dev/null || echo 000)
    if [[ "$CODE" == "200" ]]; then UP=1; break; fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then break; fi
    sleep 0.5
  done
  if [[ "$UP" == "1" ]]; then
    pass "server healthy on ${BASE_URL} (pid ${SERVER_PID}, db ${TMPDB_DIR}/e2e.db, webhook → :${WAKE_PORT}/capture)"
  else
    fail "server did not become healthy — last log lines follow"
    tail -20 "$LOG" || true
    exit 1
  fi
else
  pass "using external server at ${BASE_URL} (--base-url)"
fi

req GET /health
assert_eq "GET /health → 200" "$STATUS" "200"
assert_contains "health body says ok" "$BODY" '"ok"'

# =========================================================================
section "push every schema/golden/*.json"
for f in status-board.json question.json minimal.json dashboard.json edge-cases.json; do
  req PUT "/api/slates/$(jq -r .id "$GOLDEN_DIR/$f")" "$GOLDEN_DIR/$f"
  if [[ "$STATUS" == "200" || "$STATUS" == "201" ]]; then
    pass "push $f → $STATUS"
  else
    fail "push $f → $STATUS (wanted 200/201): ${BODY:0:200}"
  fi
done

req PUT /api/slates/wrong-id "$GOLDEN_DIR/minimal.json"
assert_eq "path/body id mismatch → 400" "$STATUS" "400"
assert_contains "mismatch error carries hint" "$BODY" '"hint"'

req PUT /api/slates/future "$GOLDEN_DIR/unknown-element.json"
assert_eq "unknown-element.json → 400" "$STATUS" "400"
assert_eq "error code is validation_error" "$(jq -r '.error.code // ""' <<<"$BODY" 2>/dev/null || echo '')" "validation_error"
HINT=$(jq -r '.error.hint // ""' <<<"$BODY" 2>/dev/null || echo '')
if [[ -n "$HINT" ]]; then
  pass "hint present and actionable"
  assert_contains "hint names the offending path" "$HINT" "children"
else
  fail "hint empty — api.md says hints are never empty"
fi

# =========================================================================
section "device roundtrip + ETag"
req GET /api/device/slates
assert_eq "GET /api/device/slates → 200" "$STATUS" "200"
NIGHTLY_HASH=$(jq -r '.slates[] | select(.slateId=="nightly") | .contentHash // empty' <<<"$BODY" 2>/dev/null || echo '')
if [[ -n "$NIGHTLY_HASH" ]]; then
  pass "nightly listed with contentHash ${NIGHTLY_HASH:0:8}…"
else
  fail "nightly missing from device list: ${BODY:0:200}"
fi

HDRS=$(mktemp)
BODY=$(curl -sS -D "$HDRS" -H "$AUTH" "$BASE_URL/api/device/slates/nightly" 2>/dev/null) || true
ETAG=$(tr -d '\r' <"$HDRS" | awk '/^[Ee][Tt]ag:/{print $2}' | tr -d '"')
assert_eq "ETag equals contentHash" "$ETAG" "$NIGHTLY_HASH"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTH" -H "If-None-Match: \"$ETAG\"" "$BASE_URL/api/device/slates/nightly" 2>/dev/null || echo 000)
assert_eq "If-None-Match with current hash → 304" "$CODE" "304"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTH" -H 'If-None-Match: "stale-hash"' "$BASE_URL/api/device/slates/nightly" 2>/dev/null || echo 000)
assert_eq "If-None-Match with stale hash → 200" "$CODE" "200"
rm -f "$HDRS"

# =========================================================================
section "interactions: answer + check batch"
BATCH='{"interactions":[
  {"seq":"e2e-0001","slateId":"job-alert","kind":"answer","elementId":"q-fail","questionId":"q-fail","optionId":"skip","optionLabel":"Skip this deploy","clientAt":"2026-09-14T23:17:02Z"},
  {"seq":"e2e-0002","slateId":"nightly","kind":"check","elementId":"bed","itemId":"garage","clientAt":"2026-09-14T23:17:09Z"}
]}'
req POST /api/device/interactions "" "$BATCH"
assert_eq "batch → 202" "$STATUS" "202"
assert_eq "accepted:2 duplicate:0" "$(jq -r '"\(.accepted):\(.duplicate)"' <<<"$BODY" 2>/dev/null || echo bad)" "2:0"

req POST /api/device/interactions "" "$BATCH"
assert_eq "replay of same seqs → 202" "$STATUS" "202"
assert_eq "replay accepted:0 duplicate:2" "$(jq -r '"\(.accepted):\(.duplicate)"' <<<"$BODY" 2>/dev/null || echo bad)" "0:2"

req POST /api/device/interactions "" '{"interactions":[{"seq":"e2e-0003","slateId":"job-alert","kind":"telepathy"}]}'
assert_eq "invalid kind → 400 (all-or-nothing)" "$STATUS" "400"
assert_contains "invalid batch error carries hint" "$BODY" '"hint"'

# server owns todo state: the check must have mutated the stored spec
req GET /api/slates/nightly
GARAGE=$(jq -r '[.children[] | select(.type=="todoList") | .items[] | select(.id=="garage") | .checked] | join(",")' <<<"$BODY" 2>/dev/null || echo '')
assert_eq "check interaction mutated stored spec (garage checked)" "$GARAGE" "true"

# =========================================================================
section "webhook wake: exactly one POST per accepted interaction, signed"
if [[ "$SKIP_BOOT" == "1" ]]; then
  printf '  (skipped — external server via --base-url, no capture listener attached)\n'
else
  WAKES_FILE="$TMPDB_DIR/wakes.jsonl"
  N=0
  for _ in $(seq 1 50); do            # ≤5s for the first wake to land
    if [[ -s "$WAKES_FILE" ]]; then
      N="$(wc -l <"$WAKES_FILE" | tr -d ' ')"
      if [[ "$N" -ge 2 ]]; then break; fi
    fi
    sleep 0.1
  done
  sleep 0.5                           # settle window — a replay wake would land here
  N="$(jq -s 'length' "$WAKES_FILE" 2>/dev/null || echo 0)"
  # accepted interactions: 2 (answer→job-alert, check→nightly); the replayed seqs
  # and the 400'd batch must not wake anything.
  assert_eq "exactly 2 wake POSTs (replayed seqs wake nothing)" "$N" "2"
  assert_eq "every wake body type is \"interactions\"" \
    "$(jq -sr '[.[].body | fromjson | .type] | unique | join(",")' "$WAKES_FILE" 2>/dev/null || echo bad)" "interactions"
  assert_eq "wake slateIds are job-alert + nightly" \
    "$(jq -sr '[.[].body | fromjson | .slateId] | sort | join(",")' "$WAKES_FILE" 2>/dev/null || echo bad)" "job-alert,nightly"
  assert_eq "each wake's interactions match its envelope slateId" \
    "$(jq -sr '[.[] | (.body | fromjson) as $w | select(($w.interactions | map(.slateId) | unique) != [$w.slateId])] | length' "$WAKES_FILE" 2>/dev/null || echo bad)" "0"

  VALID=0; TAMPER_BLOCKED=0
  while IFS= read -r line; do
    if [[ -z "$line" ]]; then continue; fi
    SIG="$(jq -r '.sig // ""' <<<"$line")"
    CALC="$(jq -j '.body' <<<"$line" | openssl dgst -sha256 -hmac "$WAKE_SECRET" -hex 2>/dev/null | awk '{print $NF}')"
    if [[ -n "$CALC" && "$SIG" == "sha256=${CALC}" ]]; then VALID=$((VALID+1)); fi
    # same signature recomputed over a tampered body must NOT match the header
    TAMPERED="$(jq -j '.body | fromjson | .slateId="tampered-by-e2e" | tostring' <<<"$line" \
      | openssl dgst -sha256 -hmac "$WAKE_SECRET" -hex 2>/dev/null | awk '{print $NF}')"
    if [[ -n "$TAMPERED" && "$SIG" != "sha256=${TAMPERED}" ]]; then TAMPER_BLOCKED=$((TAMPER_BLOCKED+1)); fi
  done <"$WAKES_FILE"
  assert_eq "every wake signature validates (HMAC-SHA256 via openssl)" "$VALID" "$N"
  assert_eq "tampered body fails signature validation" "$TAMPER_BLOCKED" "$N"

  if [[ -n "$LISTENER_PID" ]] && kill -0 "$LISTENER_PID" 2>/dev/null; then
    kill "$LISTENER_PID" 2>/dev/null || true
    wait "$LISTENER_PID" 2>/dev/null || true
    printf '  capture listener on :%s stopped\n' "$WAKE_PORT"
  fi
  LISTENER_PID=""
fi

# =========================================================================
# =========================================================================
section "body-size guard (forged Content-Length is honored on the wire)"
STATUS=$(curl -s -o "$TMPDB_DIR/big.json" -w '%{http_code}' -X PUT "${BASE_URL}/api/slates/e2e-big" \
  -H "Authorization: Bearer ${API_KEY}" -H 'Content-Type: application/json' \
  -H 'Content-Length: 99999999' --data-binary '{"id":"e2e-big","version":2,"children":[]}')
assert_eq "oversized Content-Length on PUT -> 400" "$STATUS" "400"
assert_eq "oversized PUT error code is payload_too_large" "$(jq -r '.error.code // ""' < "$TMPDB_DIR/big.json" 2>/dev/null)" "payload_too_large"

section "poll responses as the agent, verify fields, ack"
req GET "/api/slates/job-alert/responses?since=0"
assert_eq "responses → 200" "$STATUS" "200"
ANSWER=$(jq -c '[.responses[] | select(.kind=="answer")][0] // empty' <<<"$BODY" 2>/dev/null || echo '')
assert_eq "answer carries questionId" "$(jq -r '.questionId // ""'  <<<"$ANSWER" 2>/dev/null || echo '')" "q-fail"
assert_eq "answer carries optionId"   "$(jq -r '.optionId // ""'    <<<"$ANSWER" 2>/dev/null || echo '')" "skip"
assert_eq "answer echoes optionLabel" "$(jq -r '.optionLabel // ""' <<<"$ANSWER" 2>/dev/null || echo '')" "Skip this deploy"
assert_eq "answer not flagged stale"  "$(jq -r '.stale // false'    <<<"$ANSWER" 2>/dev/null || echo '')" "false"
CURSOR=$(jq -r '.cursor // 0' <<<"$BODY" 2>/dev/null || echo 0)

req GET "/api/slates/nightly/responses?since=0"
CHECK=$(jq -c '[.responses[] | select(.kind=="check")][0] // empty' <<<"$BODY" 2>/dev/null || echo '')
assert_eq "check carries itemId"              "$(jq -r '.itemId // ""'    <<<"$CHECK" 2>/dev/null || echo '')" "garage"
assert_eq "check carries elementId (list id)" "$(jq -r '.elementId // ""' <<<"$CHECK" 2>/dev/null || echo '')" "bed"

req DELETE "/api/slates/job-alert/responses?beforeId=${CURSOR}"
assert_eq "ack → 200" "$STATUS" "200"
assert_eq "ack deleted:1" "$(jq -r '.deleted // -1' <<<"$BODY" 2>/dev/null || echo bad)" "1"
req GET "/api/slates/job-alert/responses?since=0"
assert_eq "job-alert empty after ack" "$(jq -r '.responses | length' <<<"$BODY" 2>/dev/null || echo bad)" "0"
req DELETE /api/slates/nightly/responses
assert_eq "clear-responses (no beforeId) → 200" "$STATUS" "200"

# =========================================================================
if [[ -z "$WORK_DIR" ]]; then
  if [[ -n "${TMPDB_DIR:-}" && -d "$TMPDB_DIR" ]]; then
    WORK_DIR="$TMPDB_DIR"
  else
    WORK_DIR="$(mktemp -d /tmp/slate-e2e-work.XXXXXX)"; WORK_DIR_MINE=1
  fi
fi
LOOP_FILE="$WORK_DIR/e2e-loop.json"
cat >"$LOOP_FILE" <<'JSON'
{
  "id": "e2e-loop",
  "version": 2,
  "title": "Deploy gate",
  "tone": "info",
  "children": [
    {
      "type": "question",
      "id": "q-loop",
      "prompt": "Ready to deploy to production?",
      "options": [
        { "id": "ship", "label": "Ship it" },
        { "id": "staging", "label": "Staging first" },
        { "id": "abort", "label": "Abort" }
      ]
    }
  ]
}
JSON

section "agent loop: question slate → device answer → agent poll → ack → re-push"
req PUT /api/slates/e2e-loop "$LOOP_FILE"
if [[ "$STATUS" == "200" || "$STATUS" == "201" ]]; then
  pass "push fresh question slate e2e-loop → $STATUS"
else
  fail "push e2e-loop → $STATUS (wanted 200/201): ${BODY:0:200}"
fi
LOOP_HASH_1="$(jq -r '.contentHash // ""' <<<"$BODY" 2>/dev/null || echo '')"

req POST /api/device/interactions "" '{"interactions":[{"seq":"e2e-loop-0001","slateId":"e2e-loop","kind":"answer","elementId":"q-loop","questionId":"q-loop","optionId":"staging","optionLabel":"Staging first","clientAt":"2026-09-14T23:40:00Z"}]}'
assert_eq "device answers the question → 202" "$STATUS" "202"
assert_eq "answer accepted:1" "$(jq -r '.accepted // -1' <<<"$BODY" 2>/dev/null || echo bad)" "1"

req GET "/api/slates/e2e-loop/responses?since=0"
assert_eq "agent poll (?since=0) → 200" "$STATUS" "200"
LOOP_ANSWER="$(jq -c '.responses[0] // empty' <<<"$BODY" 2>/dev/null || echo '')"
assert_eq "response carries questionId" "$(jq -r '.questionId // ""'  <<<"$LOOP_ANSWER" 2>/dev/null || echo '')" "q-loop"
assert_eq "response carries optionId"   "$(jq -r '.optionId // ""'    <<<"$LOOP_ANSWER" 2>/dev/null || echo '')" "staging"
assert_eq "response echoes optionLabel" "$(jq -r '.optionLabel // ""' <<<"$LOOP_ANSWER" 2>/dev/null || echo '')" "Staging first"
assert_eq "response not flagged stale"  "$(jq -r '.stale // false'    <<<"$LOOP_ANSWER" 2>/dev/null || echo '')" "false"
LOOP_CURSOR="$(jq -r '.cursor // 0' <<<"$BODY" 2>/dev/null || echo 0)"
LOOP_RID="$(jq -r '.responses[0].id // ""' <<<"$BODY" 2>/dev/null || echo '')"
assert_eq "cursor == the response id" "$LOOP_CURSOR" "$LOOP_RID"

req DELETE "/api/slates/e2e-loop/responses?beforeId=${LOOP_CURSOR}"
assert_eq "ack with beforeId==cursor → 200" "$STATUS" "200"
assert_eq "ack deleted:1" "$(jq -r '.deleted // -1' <<<"$BODY" 2>/dev/null || echo bad)" "1"
req GET "/api/slates/e2e-loop/responses?since=0"
assert_eq "responses empty after ack" "$(jq -r '.responses | length' <<<"$BODY" 2>/dev/null || echo bad)" "0"

req PUT /api/slates/e2e-loop "$LOOP_FILE"
if [[ "$STATUS" == "200" || "$STATUS" == "201" ]]; then
  pass "re-push of identical question slate → $STATUS"
else
  fail "re-push e2e-loop → $STATUS (wanted 200/201): ${BODY:0:200}"
fi
LOOP_HASH_2="$(jq -r '.contentHash // ""' <<<"$BODY" 2>/dev/null || echo '')"
assert_eq "re-push contentHash stable" "$LOOP_HASH_2" "$LOOP_HASH_1"

# =========================================================================
section "skill smoke: slate.sh push/get/list/responses/ack/clear-responses"
if [[ ! -f "$SKILL_CLI" ]]; then
  fail "skill CLI not found: $SKILL_CLI"
else
  SKILL_FILE="$WORK_DIR/e2e-skill.json"
  cat >"$SKILL_FILE" <<'JSON'
{
  "id": "e2e-skill",
  "version": 2,
  "title": "Skill smoke",
  "children": [
    { "type": "text", "text": "pushed by skill/slate/scripts/slate.sh during e2e" }
  ]
}
JSON
  run_cli() { # runs slate.sh against the booted backend; sets CLI_RC + CLI_OUT
    CLI_RC=0
    CLI_OUT="$(SLATE_URL="$BASE_URL" SLATE_API_KEY="$API_KEY" "$SKILL_CLI" "$@" 2>&1)" || CLI_RC=$?
  }

  run_cli push "$SKILL_FILE" e2e-skill
  assert_eq "slate.sh push → exit 0" "$CLI_RC" "0"
  assert_contains "push prints slateId:" "$CLI_OUT" "slateId: e2e-skill"
  assert_contains "push prints contentHash:" "$CLI_OUT" "contentHash: "

  run_cli get e2e-skill
  assert_eq "slate.sh get → exit 0" "$CLI_RC" "0"
  assert_contains "get returns the stored slate" "$CLI_OUT" '"e2e-skill"'

  run_cli list
  assert_eq "slate.sh list → exit 0" "$CLI_RC" "0"
  assert_contains "list shows e2e-skill" "$CLI_OUT" "slateId: e2e-skill"
  assert_contains "list shows contentHash" "$CLI_OUT" "contentHash: "

  run_cli responses e2e-skill
  assert_eq "slate.sh responses → exit 0" "$CLI_RC" "0"
  assert_contains "responses prints cursor" "$CLI_OUT" '"cursor"'

  run_cli ack e2e-skill
  assert_eq "slate.sh ack → exit 0" "$CLI_RC" "0"
  assert_contains "ack prints deleted:" "$CLI_OUT" "deleted:"

  run_cli clear-responses e2e-skill
  assert_eq "slate.sh clear-responses → exit 0" "$CLI_RC" "0"
  assert_contains "clear-responses prints deleted:" "$CLI_OUT" "deleted: 0 (all responses for e2e-skill)"

  BAD_FILE="$WORK_DIR/e2e-invalid.json"
  printf '{"id": "e2e-invalid", "version": 2, "children": [' >"$BAD_FILE"
  run_cli validate "$BAD_FILE"
  if [[ "$CLI_RC" -ne 0 ]]; then
    pass "slate.sh validate rejects invalid JSON (exit $CLI_RC)"
  else
    fail "slate.sh validate accepted an invalid file (exit 0)"
  fi
fi

# =========================================================================
section "summary"
printf '\n  %d/%d checks passed\n' "$PASS" "$((PASS+FAIL))"
if [[ "$FAIL" -gt 0 ]]; then
  printf '  \033[31mE2E: FAIL (%d failing checks)\033[0m\n' "$FAIL"
  exit 1
fi
printf '  \033[32mE2E: PASS\033[0m\n'
exit 0
