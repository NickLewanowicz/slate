#!/usr/bin/env bash
# slate.sh — Slate CLI for AI agents (v2 API, see schema/api.md)
# Requires: curl, jq. Config via env: SLATE_URL, SLATE_API_KEY, SLATE_WIDGET (default "home").
# Exit codes: 0 ok | 1 usage | 2 config | 3 network | 4 API error | 5 local validation failed
set -euo pipefail

VERSION="2.0.0"
SLATE_WIDGET="${SLATE_WIDGET:-home}"

# ---- output helpers (colors only on a TTY; plain when piped) ----
if [[ -t 1 ]] && [[ -z "${NO_COLOR:-}" ]]; then
  C_OK=$'\033[32m'; C_ERR=$'\033[31m'; C_DIM=$'\033[2m'; C_0=$'\033[0m'
else
  C_OK=""; C_ERR=""; C_DIM=""; C_0=""
fi
info() { printf '%s\n' "$*"; }
ok()   { printf '%s%s%s\n' "$C_OK" "$*" "$C_0"; }
die()  { local code=$1; shift; printf '%serror: %s%s\n' "$C_ERR" "$*" "$C_0" >&2; exit "$code"; }
EX_USAGE=1; EX_CONFIG=2; EX_NETWORK=3; EX_API=4; EX_VALIDATE=5

usage() {
  cat <<'USAGE'
slate.sh — Slate CLI for AI agents

Usage: slate.sh <command> [args]

Commands:
  ping                          Check server reachability + auth (GET /api/ping)
  validate FILE                 Validate a slate JSON locally (no network)
  push FILE [SLATE_ID]          Create/update a slate (full replace). Default id: $SLATE_WIDGET
  get SLATE_ID                  Print the stored slate (server-owned todo state applied)
  list                          List slates (slateId, tone, updatedAt, contentHash)
  delete SLATE_ID               Delete a slate (cascades its responses)
  responses SLATE_ID [SINCE]    Print new responses since interaction id SINCE (default 0); prints cursor
  ack SLATE_ID [BEFORE_ID]      Delete responses up to BEFORE_ID (omit = ALL responses for the slate)
  clear-responses SLATE_ID      Delete ALL responses for the slate

Env: SLATE_URL, SLATE_API_KEY (required for everything except validate), SLATE_WIDGET (default "home").
Exit codes: 0 ok, 1 usage, 2 config, 3 network, 4 API error, 5 local validation failed.
USAGE
}

need_env() {
  [[ -n "${SLATE_URL:-}" ]]     || die $EX_CONFIG "SLATE_URL is not set (e.g. export SLATE_URL=http://slate-host:3000)"
  [[ -n "${SLATE_API_KEY:-}" ]] || die $EX_CONFIG "SLATE_API_KEY is not set (export it — every /api call sends it as Bearer)"
  SLATE_URL="${SLATE_URL%/}"    # tolerate trailing slash
}

# ---- HTTP core: sets HTTP_STATUS and HTTP_BODY ----
DATA_FILE=""; DATA_RAW=""
http() {
  local method=$1 path=$2 tmp args
  tmp=$(mktemp) || die $EX_NETWORK "mktemp failed"
  args=( -sS -o "$tmp" -w '%{http_code}' -X "$method"
         -H "Authorization: Bearer ${SLATE_API_KEY}" )
  if [[ -n "$DATA_FILE" ]]; then
    args+=( -H 'Content-Type: application/json' --data-binary "@${DATA_FILE}" )
  elif [[ -n "$DATA_RAW" ]]; then
    args+=( -H 'Content-Type: application/json' --data-binary "${DATA_RAW}" )
  fi
  if ! HTTP_STATUS=$(curl "${args[@]}" "${SLATE_URL}${path}" 2>"$tmp.err"); then
    local msg; msg=$(cat "$tmp.err"); rm -f "$tmp" "$tmp.err"
    die $EX_NETWORK "could not reach ${SLATE_URL} (${msg:-curl failed}) — is the Slate backend up and SLATE_URL correct?"
  fi
  rm -f "$tmp.err"
  HTTP_BODY=$(cat "$tmp"); rm -f "$tmp"
}

# Non-2xx: print the server's error envelope and exit 4.
# error.hint is written for you (an LLM): it names the bad path and the fix. Read it, fix, retry.
fail_api() {
  {
    printf '%sAPI error %s%s\n' "$C_ERR" "$HTTP_STATUS" "$C_0"
    printf 'code: %s\n'    "$(jq -r '.error.code // "(no envelope)"' <<<"$HTTP_BODY" 2>/dev/null || echo "(non-JSON body)")"
    printf 'message: %s\n' "$(jq -r '.error.message // ""' <<<"$HTTP_BODY" 2>/dev/null || true)"
    local hint; hint=$(jq -r '.error.hint // ""' <<<"$HTTP_BODY" 2>/dev/null || true)
    if [[ -n "$hint" ]]; then
      printf 'hint: %s\n' "$hint"
    elif [[ -n "$HTTP_BODY" ]]; then
      printf 'body: %s\n' "$HTTP_BODY"
    fi
  } >&2
  exit $EX_API
}
expect_2xx() { case "$HTTP_STATUS" in 2??) return 0;; *) fail_api;; esac; }

# ---- local validation (no network) ----
cmd_validate() {
  local file=${1:-}
  [[ -n "$file" ]]  || { usage >&2; die $EX_USAGE "validate FILE — missing argument"; }
  [[ -f "$file" ]]  || die $EX_USAGE "validate FILE — file not found: $file"
  jq -e . "$file" >/dev/null 2>&1 || die $EX_VALIDATE "$file is not valid JSON"

  local problems=()
  jq -e 'has("id") and has("version") and has("children")' "$file" >/dev/null \
    || problems+=("missing required field(s): id, version, children are all required")

  # One structural walk: totals + rule violations. Arrays are lists of offender ids/types.
  local v
  v=$(jq -c '
    def depth: if type == "object" and ((.children // []) | length) > 0
               then 1 + ([.children[] | depth] | max) else 0 end;
    def isslug: type == "string" and test("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,39}$");
    [ .. | objects | select(has("type")) ] as $els
    | {
        total:   ($els | length),
        depth:   (([.children[]? | depth] | max // 0) + 1),
        maxper:  ([$els[] | select(.type == "column" or .type == "row")] | map((.children // []) | length) | max // 0),
        badtype: [ $els[] | select(.type as $t
                     | ["column","row","text","statusRow","todoList","question","progress","divider","spacer"]
                     | index($t) | not) | .type ],
        badslug: [ .. | objects | select(has("id")) | .id | select(isslug | not) ],
        badtone: [ .. | objects | select(has("tone")) | .tone | select(. as $t | ["ok","warn","error","info","neutral"] | index($t) | not) ],
        badtext: [ $els[] | select(.type == "text") | select((.text // null) | type != "string") | (.id // "?") ],
        badq:    [ $els[] | select(.type == "question")
                     | select((.options | length) < 2 or (.options | length) > 6
                              or any(.options[]; (has("id") | not) or (has("label") | not))) | (.id // "?") ],
        badtodo: [ $els[] | select(.type == "todoList")
                     | select((has("id") | not) or ((.items // []) | length) < 1 or ((.items // []) | length) > 12
                              or any(.items[]; (has("id") | not) or (has("label") | not))) | (.id // "?") ],
        badprog: [ $els[] | select(.type == "progress")
                     | select(((has("value") or .indeterminate == true) | not)) | (.id // "?") ]
      }
    | with_entries(.value |= (if type == "array" then join(",") else tostring end))
  ' "$file") || die $EX_VALIDATE "top level must be an object with id/version/children"

  local total depth maxper badtype badslug badtone badtext badq badtodo badprog
  jget() { jq -r --arg k "$1" '.[$k] // ""' <<<"$v"; }
  total=$(jget total) depth=$(jget depth) maxper=$(jget maxper)
  badtype=$(jget badtype) badslug=$(jget badslug) badtone=$(jget badtone) badtext=$(jget badtext)
  badq=$(jget badq) badtodo=$(jget badtodo) badprog=$(jget badprog)
  (( total  <= 60 )) || problems+=("too many elements: $total (max 60)")
  (( depth  <= 6  )) || problems+=("nesting too deep: depth $depth (max 6)")
  (( maxper <= 12 )) || problems+=("a container has $maxper children (max 12 per column/row)")
  [[ -z "$badtype" ]] || problems+=("unknown element type(s): $badtype — pick from column row text statusRow todoList question progress divider spacer (typo? the backend rejects unknown types)")
  [[ -z "$badslug" ]] || problems+=("id(s) not slugs: $badslug — pattern ^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,39}$ ; ids must be stable across pushes")
  [[ -z "$badtone" ]] || problems+=("unknown tone(s): $badtone — tone must be ok|warn|error|info|neutral (semantic only, no raw colors)")
  [[ -z "$badtext" ]] || problems+=("text element(s) missing a string 'text' field: $badtext")
  [[ -z "$badq"    ]] || problems+=("question element(s) need 2–6 options, each with id+label: $badq")
  [[ -z "$badtodo" ]] || problems+=("todoList element(s) need id + 1–12 items each with id+label: $badtodo")
  [[ -z "$badprog" ]] || problems+=("progress element(s) need value (0–100) or indeterminate:true: $badprog")

  if (( ${#problems[@]} > 0 )); then
    local p
    for p in "${problems[@]}"; do printf '  %s✗%s %s\n' "$C_ERR" "$C_0" "$p" >&2; done
    printf '%sinvalid: %d problem(s) in %s%s\n' "$C_ERR" "${#problems[@]}" "$file" "$C_0" >&2
    exit $EX_VALIDATE
  fi
  printf '  %s✓%s valid: id=%s elements=%s depth=%s\n' "$C_OK" "$C_0" "$(jq -r .id "$file")" "$total" "$depth"
}

# ---- commands ----
cmd_push() {
  local file=${1:-} sid=${2:-$SLATE_WIDGET}
  [[ -n "$file" ]]  || { usage >&2; die $EX_USAGE "push FILE [SLATE_ID] — missing FILE"; }
  [[ -f "$file" ]]  || die $EX_USAGE "push FILE — file not found: $file"
  jq -e . "$file" >/dev/null 2>&1 || die $EX_USAGE "push FILE — $file is not valid JSON"
  local body_id; body_id=$(jq -r '.id // ""' "$file")
  if [[ -n "$body_id" && "$body_id" != "$sid" ]]; then
    die $EX_USAGE "body id '$body_id' != target slate '$sid' — the API requires the path id to equal the body id. Push with the id as the argument: slate.sh push $file $body_id  (or edit the body's \"id\" field to \"$sid\" only if you really mean to update '$sid')."
  fi
  ( cmd_validate "$file" ) || die $EX_VALIDATE "fix the problems above before pushing (cheaper than a 400)"
  DATA_FILE="$file" http PUT "/api/slates/${sid}"
  expect_2xx
  jq -r '"slateId: \(.slateId)\nupdatedAt: \(.updatedAt)\ncontentHash: \(.contentHash)\ncreated: \(.created)"' <<<"$HTTP_BODY"
}

cmd_get() {
  local sid=${1:-$SLATE_WIDGET}
  [[ -n "$sid" ]] || { usage >&2; die $EX_USAGE "get SLATE_ID"; }
  http GET "/api/slates/${sid}"; expect_2xx
  jq . <<<"$HTTP_BODY"
}

cmd_delete() {
  local sid=${1:-}
  [[ -n "$sid" ]] || { usage >&2; die $EX_USAGE "delete SLATE_ID"; }
  http DELETE "/api/slates/${sid}"; expect_2xx
  ok "deleted: $sid"
}

cmd_list() {
  http GET "/api/slates"; expect_2xx
  jq -r 'if (.slates | length) == 0 then "(no slates)" else
         .slates[] | "slateId: \(.slateId)  tone: \(.tone)  updatedAt: \(.updatedAt)  contentHash: \(.contentHash)" end' <<<"$HTTP_BODY"
}

cmd_responses() {
  local sid=${1:-$SLATE_WIDGET} since=${2:-0}
  [[ -n "$sid" ]] || { usage >&2; die $EX_USAGE "responses SLATE_ID [SINCE]"; }
  http GET "/api/slates/${sid}/responses?since=${since}"; expect_2xx
  jq . <<<"$HTTP_BODY"
  local cursor; cursor=$(jq -r '.cursor' <<<"$HTTP_BODY")
  printf '%s# next poll: slate.sh responses %s %s   ack after processing: slate.sh ack %s %s%s\n' \
    "$C_DIM" "$sid" "$cursor" "$sid" "$cursor" "$C_0" >&2
}

cmd_ack() {
  local sid=${1:-$SLATE_WIDGET} before=${2:-}
  [[ -n "$sid" ]] || { usage >&2; die $EX_USAGE "ack SLATE_ID [BEFORE_ID]"; }
  local qs=""
  if [[ -n "$before" ]]; then qs="?beforeId=${before}"; fi
  http DELETE "/api/slates/${sid}/responses${qs}"; expect_2xx
  jq -r '"deleted: \(.deleted)"' <<<"$HTTP_BODY"
}

cmd_clear_responses() {
  local sid=${1:-$SLATE_WIDGET}
  [[ -n "$sid" ]] || { usage >&2; die $EX_USAGE "clear-responses SLATE_ID"; }
  http DELETE "/api/slates/${sid}/responses"; expect_2xx
  jq -r '"deleted: \(.deleted) (all responses for '"$sid"')"' <<<"$HTTP_BODY"
}

cmd_ping() {
  http GET "/api/ping"; expect_2xx
  jq -r '"ok: \(.ok)  serverTime: \(.serverTime)  version: \(.version)"' <<<"$HTTP_BODY"
}

# ---- dispatch ----
case "${1:-}" in
  validate)        shift; cmd_validate "$@" ;;
  push)            shift; need_env; cmd_push "$@" ;;
  get)             shift; need_env; cmd_get "$@" ;;
  delete)          shift; need_env; cmd_delete "$@" ;;
  list)            shift; need_env; cmd_list "$@" ;;
  responses)       shift; need_env; cmd_responses "$@" ;;
  ack)             shift; need_env; cmd_ack "$@" ;;
  clear-responses) shift; need_env; cmd_clear_responses "$@" ;;
  ping)            shift; need_env; cmd_ping "$@" ;;
  help|-h|--help)  usage ;;
  --version)       info "slate.sh ${VERSION}" ;;
  "")              usage >&2; exit $EX_USAGE ;;
  *)               usage >&2; die $EX_USAGE "unknown command: $1" ;;
esac
