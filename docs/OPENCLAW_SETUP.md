# OpenClaw setup — Slate as an agent skill

How to install the Slate skill into an OpenClaw instance and wire it to crons
so questions get answered without ever stalling a session.

## 1. Install the skill

OpenClaw discovers skills in two places: the user-level skills dir and the
agent workspace's `skills/` dir. Copy the whole directory (SKILL.md must sit at
its root):

```bash
# user-level (available to every agent/session)
mkdir -p ~/.openclaw/skills
cp -R skill/slate ~/.openclaw/skills/slate

# or workspace-level (this agent only)
cp -R skill/slate <workspace>/skills/slate

chmod +x ~/.openclaw/skills/slate/scripts/slate.sh
```

Requirements on the gateway host: `curl` and `jq` (the skill declares them via
`requires.bins`; OpenClaw checks before loading). No other dependencies.

## 2. Configure the environment

The skill reads three variables:

```bash
export SLATE_URL="http://slate.example.com:3000"   # your backend (LAN IP from inside docker)
export SLATE_API_KEY="…long random string…"      # must match the backend's SLATE_API_KEY
export SLATE_WIDGET="home"                        # optional, default "home"
```

Two ways to provide them:

- **Skill env injection (preferred).** If your OpenClaw config supports per-skill env, set
  `SLATE_URL` / `SLATE_API_KEY` there so the values live with the skill, not the shell.
- **Plain export** in the gateway service's environment (systemd `Environment=`,
  `docker exec -e …`, or the workspace `.env` your agent sources).

Smoke-test as the agent would:

```bash
~/.openclaw/skills/slate/scripts/slate.sh ping
# ok: true  serverTime: 2026-09-14T21:04:11.512Z  version: 2.0.0
~/.openclaw/skills/slate/scripts/slate.sh validate ~/.openclaw/skills/slate/examples/question.json
```

## 3. The cron pattern: push, then END

This is the whole point of Slate, so it bears repeating. **A cron that needs a
decision pushes the question and then ends.** It never waits in-session for the
answer; a separate follow-up run picks the answer up.

```
┌─ 23:00 cron: nightly-check ────────────────────────────┐
│  slate.sh push question.json home   # ask              │
│  (do any other work)                                   │
│  END — session closes                                  │
└────────────────────────────────────────────────────────┘
        user taps "Purge" on the widget at 23:17 (offline? it queues)
┌─ 23:20 cron: nightly-followup ─────────────────────────┐
│  slate.sh responses home $CURSOR    # read             │
│  act on the answer (run the purge…)                    │
│  slate.sh ack home $CURSOR          # mark handled     │
│  slate.sh push updated-board.json   # close the loop   │
│  END                                                   │
└────────────────────────────────────────────────────────┘
```

The follow-up can be a plain cron every N minutes (fine for minutes-scale
decisions) or a webhook wake (below) when you want seconds.

Why the follow-up must be a separate session: OpenClaw sessions can't receive
the answer mid-run, and "wait" tools like `ask_user` block the session with
`recovery=none` — if the user is away, the cron hangs until timeout and the
question is effectively lost. Push-then-end degrades gracefully: unanswered
questions just grey out at `expiresAt`, and the next cron picks the answer up
whenever it arrives.

**DO NOT** replace this with `ask_user` (blocking) inside a cron. Rationale:
- `ask_user` with no recovery path stalls the session; ~28 home crons × one
  stalled question each is how you end up with forgotten memory-file questions.
- Slate answers are durable (persisted server-side, acked explicitly); blocked
  sessions are not.
- The widget IS the notification. If the user is around, they answer in
  seconds; if not, nothing is lost.

## 4. Follow-up poll via webhook wake (optional, lower latency)

Polling is the source of truth. The webhook only wakes the agent sooner. When
the backend runs with `SLATE_WEBHOOK_URL` + `SLATE_WEBHOOK_SECRET` set, every
new interaction triggers a signed POST:

```json
POST <SLATE_WEBHOOK_URL>
X-Slate-Signature: sha256=<hex hmac-sha256(body, SLATE_WEBHOOK_SECRET)>

{ "type": "interactions", "slateId": "home", "interactions": [ … ] }
```

With an OpenClaw gateway hook (create a hook that accepts external POSTs, e.g.
`POST https://gateway.local/hooks/slate-wake`):

1. Set `SLATE_WEBHOOK_URL=https://gateway.local/hooks/slate-wake` and
   `SLATE_WEBHOOK_SECRET=…` on the backend (env — see `backend/docker-compose.yml`).
2. Point the hook at a one-shot agent run whose instruction is, in full:
   > You were woken because the user interacted with their Slate widget.
   > Run `slate.sh responses home` and handle the new answers (act, ack,
   > update the slate to close the loop). If there are no new responses,
   > do nothing.
3. Verify the `X-Slate-Signature` header in front of the hook if it is
   reachable beyond localhost (snippet in [`API_EXAMPLES.md`](API_EXAMPLES.md)).

The wake handler still polls (`slate.sh responses`) rather than trusting the
payload — webhooks are fire-and-forget with retries, so the poll is what makes
delivery guaranteed and acked.

## 5. Deploy the backend (docker compose)

The self-hosted backend ships as a single container: [`backend/docker-compose.yml`](../backend/docker-compose.yml)
(Bun + SQLite in WAL mode, `HEALTHCHECK` on `/health`).

```bash
cd backend
export SLATE_API_KEY="$(openssl rand -hex 32)"   # keep this; the app + skill need it
SLATE_API_KEY="$SLATE_API_KEY" docker compose up -d --build

curl -s http://localhost:3000/health             # {"status":"ok"}
```

Environment (all optional except the key): `SLATE_PORT` (3000), `SLATE_DB_PATH`
(`./data/slate.db`, mounted volume), `SLATE_WEBHOOK_URL`, `SLATE_WEBHOOK_SECRET`,
`SLATE_MAX_RESPONSES_PER_SLATE` (200), `SLATE_RESPONSE_TTL_HOURS` (168).

From the phone on the same LAN, point the app at `http://<host>:3000` and paste
the key; the in-app **Test** button hits `/api/ping`. From an Android emulator
during development the host is `http://10.0.2.2:3000`.

> The previous `slate-backend` deployment at slate.example.com runs the v1 API.
> The v2 backend is a fresh stack — do not repoint it until the v2 migration is
> explicitly scheduled.

## 6. Verify the full loop once by hand

```bash
slate.sh push ~/.openclaw/skills/slate/examples/question.json job-alert   # widget shows the question
slate.sh responses home                                              # empty until you tap
# tap an answer on the widget (or in the app)…
slate.sh responses home                                              # your answer appears
slate.sh ack home "$(slate.sh responses home | jq -r .cursor)"
```

Then schedule the crons and let the agent take over.
