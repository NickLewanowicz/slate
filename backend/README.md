# Slate backend (v2)

Bun + Elysia + `bun:sqlite` API server for Slate: agents push declarative JSON
slates, devices render them, and tap interactions come back for the agent to poll.

## Quick start

```bash
cd backend
bun install
SLATE_API_KEY=$(openssl rand -hex 32) bun run dev   # listens on :3000
```

`SLATE_API_KEY` is **required** — boot fails without it, and every `/api` route
needs `Authorization: Bearer <SLATE_API_KEY>`. Set `SLATE_PORT` / `SLATE_DB_PATH`
to move off `:3000` / `./data/slate.db`.

## Scripts

| Command | What it does |
|---|---|
| `bun run dev` | Start the server (`src/index.ts`) |
| `bun run start` | Same as `dev` |
| `bun test` | Route-level tests over in-memory SQLite (no listening socket) |
| `bun run typecheck` | `tsc --noEmit` |
| `bun run coverage` | Tests with lcov coverage; fails below the 80% gate |

## Environment

Copy `backend/.env.example` to `backend/.env` as a starting point. All vars are
parsed by `src/config.ts`; invalid values fail fast at boot with a hint.

| Variable | Required | Default | Notes |
|---|---|---|---|
| `SLATE_API_KEY` | yes | — | Boot fails if unset or blank |
| `SLATE_PORT` | no | `3000` | Integer 1–65535 |
| `SLATE_DB_PATH` | no | `./data/slate.db` | SQLite file (WAL); compose sets `/data/slate.db` |
| `SLATE_WEBHOOK_URL` | no | unset | http(s) URL; unset disables webhook wake |
| `SLATE_WEBHOOK_SECRET` | no | unset | Set → sends `X-Slate-Signature: sha256=<hex hmac>` |
| `SLATE_MAX_RESPONSES_PER_SLATE` | no | `200` | Integer ≥ 1 |
| `SLATE_RESPONSE_TTL_HOURS` | no | `168` | Retention window; integer ≥ 0 |
| `SLATE_LOG_LEVEL` | no | `info` | One of `debug\|info\|warn\|error` |
| `SLATE_SWEEP_DISABLED` | no | unset | `1` disables the retention sweep (used by tests) |

## API

The frozen contract is [`../schema/api.md`](../schema/api.md) — routes, envelopes,
error codes, and examples. `slate.schema.json` / `interactions.schema.json` are
the validation sources of truth (unknown fields are 400s with hints, by design).

## Health & webhooks

- `GET /health` and `GET /` are unauthenticated; the Docker `HEALTHCHECK` polls `/health`.
- Each new interaction POSTs a wake-up to `SLATE_WEBHOOK_URL`, HMAC-signed when `SLATE_WEBHOOK_SECRET` is set.
- Webhook delivery retries after 2s, 8s, and 30s; polling `GET /api/slates/{id}/responses` always works as a fallback.

## Docker

The build context is the **repo root** (the Dockerfile copies `schema/*.json`):

```bash
docker build -f backend/Dockerfile -t slate-backend .
```

Or run the full stack: `docker compose -f backend/docker-compose.yml up -d`.

## Tests & coverage

```bash
bun test                        # full suite
bun test tests/slates.test.ts   # single file
bun run coverage                # lcov + 80% branch gate (scripts/check-coverage.mjs)
bun run typecheck
```
