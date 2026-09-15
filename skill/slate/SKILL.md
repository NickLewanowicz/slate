---
name: slate
description: Push status updates and questions to the user's phone Slate widget and read their answers. Use when a cron/job needs a decision, a status board refresh, or yes/no input.
version: 2.0.0
author: nick
metadata:
  openclaw:
    emoji: 📱
    requires:
      bins:
        - curl
        - jq
---

# Slate — the surface on the user's phone

Slate is a home-screen widget. You push a small JSON document (a "slate");
the user glances at it and taps a reply; you read the reply on your next
poll. One round trip, no chat window, no blocking.

**Rule zero: never block waiting for an answer.** Push, then end your turn.
Pick up the answer on a later poll (cron) or a webhook wake. Asking and
waiting in the same session is exactly the failure mode Slate replaces.

## Config (environment)

| Variable        | Required | Default | Meaning                                          |
|-----------------|----------|---------|--------------------------------------------------|
| `SLATE_URL`     | yes      | —       | Backend base URL, e.g. `http://slate:3000`       |
| `SLATE_API_KEY` | yes      | —       | Bearer token sent on every `/api` call           |
| `SLATE_WIDGET`  | no       | `home`  | Slate id used when a command omits the id        |

Check the connection first: `slate.sh ping` → `{"ok":true,...}`.

## The fast path: `scripts/slate.sh`

`slate.sh` wraps every endpoint below with good errors. Prefer it over raw
curl. All commands print plain `key: value` lines safe to paste into context.

```bash
slate.sh ping                             # verify URL + key
slate.sh validate FILE                    # local schema pre-check, no network
slate.sh push FILE [SLATE_ID]             # create/update a slate (full replace)
slate.sh get SLATE_ID                     # read current slate + server todo state
slate.sh list                             # all slates (id, tone, updatedAt, hash)
slate.sh delete SLATE_ID                  # remove a slate
slate.sh responses SLATE_ID [SINCE]       # new answers since interaction id SINCE; prints cursor
slate.sh ack SLATE_ID [BEFORE_ID]         # mark handled: delete responses up to BEFORE_ID
slate.sh clear-responses SLATE_ID         # delete ALL responses for the slate
```

Exit codes: `0` ok · `1` usage · `2` missing config · `3` network down ·
`4` API error (envelope printed) · `5` local validation failed.
On any API error the script prints the server's `error.code`, `error.message`
and `error.hint` — **the hint tells you how to fix the payload. Read it.**

## Loop A — status push (no answer expected)

Push a status board, end. Nothing to poll.

```bash
cat > /tmp/nightly.json <<'EOF'
{
  "id": "home", "version": 2, "title": "🌙 Nightly", "tone": "warn",
  "children": [
    { "type": "statusRow", "icon": "🏠", "label": "Home Assistant", "value": "ok", "tone": "ok", "detail": "checked 2m ago" },
    { "type": "statusRow", "icon": "💾", "label": "Backup", "value": "failed", "tone": "error", "detail": "disk full — 412 GB used" },
    { "type": "divider" },
    { "type": "text", "text": "Before bed", "style": "heading" },
    { "type": "todoList", "id": "bed", "items": [
      { "id": "doors", "label": "Lock doors", "checked": true },
      { "id": "garage", "label": "Close garage" }
    ]}
  ]
}
EOF
slate.sh validate /tmp/nightly.json && slate.sh push /tmp/nightly.json
# → slateId: home   updatedAt: 2026-09-14T21:04:11Z   contentHash: 9f2c…   created: false
```

## Loop B — question with canned answers (the decision loop)

1. Push a `question` element. Give it a **stable id** (e.g. `q-backup`).
2. End your turn / run other work.
3. Later: `slate.sh responses home <cursor>` → read `optionId` + `optionLabel`.
4. `slate.sh ack home <cursor>` so the answer is not re-delivered.
5. Close the loop: re-push the slate with the question replaced by a status row
   ("Backup skipped tonight ✓"). The user sees you acted.

```bash
slate.sh push examples/question.json job-alert # fresh slate from the bundled example
slate.sh responses job-alert                   # right after push: empty — that is fine
# …later:
CURSOR=$(slate.sh responses job-alert | jq -r .cursor)
slate.sh ack job-alert "$CURSOR"
```

A tap on the widget arrives as:
```json
{ "id": 12, "slateId": "home", "kind": "answer", "elementId": "q-backup",
  "questionId": "q-backup", "optionId": "skip", "optionLabel": "Skip tonight",
  "clientAt": "…", "createdAt": "…", "stale": false }
```
`optionLabel` is echoed for you — no lookup needed. `stale: true` means the
user answered after the question's `expiresAt`; still valid, just late.

## Loop C — free text (`allowText: true`)

Widgets cannot host text fields, so set `"allowText": true` on the question.
The widget shows a "reply in app" row; the user types in the companion app.
The reply arrives as `kind: "text"` with the string in `value`:

```json
{ "kind": "text", "questionId": "q-backup", "value": "rotate the token first, then retry" }
```

Use canned options when 2–6 labels cover the likely answers; use `allowText`
when the user needs to say something you did not predict. You can combine both.

## Element cheatsheet (the only 9)

| Element      | Required            | Key props (default)                              | Use for |
|--------------|---------------------|--------------------------------------------------|---------|
| `column`     | `children`          | `gap` 0–24 (8), `padding` 0–24 (0; root 16), `align` (start) | Vertical group; root container |
| `row`        | —                   | `children`, `gap` (8), `gravity` (start; `spaceBetween` pins last child right) | Horizontal pair, e.g. label + value |
| `text`       | `text`              | `style`: title/heading/body/caption (body), `tone`, `maxLines` 1–10 | Prose, headings, captions |
| `statusRow`  | `label`             | `icon` ≤4 chars (•), `label` ≤80, `value` ≤80 (right, tone-colored), `detail` ≤160 (2nd line), `tone` | THE status line: service + state |
| `todoList`   | `id`, `items` 1–12  | item: `id`, `label` ≤120, `checked`, `tone`       | Checkable list; `id` is echoed in check/uncheck |
| `question`   | `id`, `prompt`, `options` 2–6 | option: `id`, `label` ≤40, `style`: primary/danger/quiet (quiet); `allowText`, `placeholder` | Ask + one-tap answer |
| `progress`   | —                   | `value` 0–100 (or `indeterminate`), `label`, `tone` | Percent, busy spinner |
| `divider`    | —                   | `inset` (false)                                   | Separator |
| `spacer`     | —                   | `height` 2–48 dp (8)                              | Breathing room |

**Tones** (semantic, never colors): `ok` green · `warn` amber · `error` red ·
`info` blue · `neutral` grey (default). Orange-ish emphasis → `warn`. The
surface `tone` colors the header rule and drives the aggregate badge.

**Style choices:** `primary` = the recommended action (one per question),
`danger` = destructive/irreversible (e.g. "Never ask again"), `quiet` = rest.

## Common mistakes (each is a rejected push)

- **Typos are rejected, not ignored.** Unknown element types, unknown tones
  (`"chartreuse"`), unknown props (`"colour"`), missing `version: 2` — all 400.
  Fix what the `hint` names and retry the SAME push; nothing is partially applied.
- **`id` mismatch:** path id must equal body `id`. `push /tmp/x.json nightly`
  with `"id": "home"` in the body → 400. slate.sh checks this before sending.
- **Unstable ids:** element ids must be stable slugs (`^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,39}$`).
  Reuse the same `id` for the same logical element across pushes — the widget
  keeps user state (todo checks) keyed on it. Do NOT embed timestamps in ids.
- **Raw visuals:** no hex colors, no font names, no images. `icon` takes an
  emoji/glyph, not `"fa-home"`. Say `tone: "error"`, never `color: "#f00"`.
- **Too much:** depth ≤ 6, ≤ 60 elements total, ≤ 12 per container, ≤ 64 KB
  serialized, `text` ≤ 500 chars, `prompt` ≤ 300. Trim `detail`s before
  splitting into a second slate.
- **PATCHing:** does not exist. Pushes are full replaces — send the whole
  envelope every time (include the todos you did not change, with their
  server-owned `checked` state from `slate.sh get` if you cached it).

## Reading answers: the poll loop

```bash
# Every poll: fetch new interactions since the last cursor, process, ack.
CURSOR=0                          # persist your cursor between runs (0 = from the start)
OUT=$(slate.sh responses home "$CURSOR")
echo "$OUT" | jq -c '.responses[]'
NEW_CURSOR=$(echo "$OUT" | jq -r '.cursor')
HAS_MORE=$(echo "$OUT" | jq -r '.hasMore')   # if true, poll again with NEW_CURSOR
# …process (act on answers, mark todos done)…
slate.sh ack home "$NEW_CURSOR"              # deletes up to beforeId; prints deleted: N
```

Rules: responses ascend by `id`; `cursor` = last id seen (echo it back as
`since`); ack **after** processing, never before. Ack with `beforeId` keeps
anything that arrived mid-poll. `slate.sh ack home` with no id wipes ALL
responses for the slate — only when you truly want a reset.

**Webhook wake (lower latency, optional).** If the gateway registers a hook,
the server POSTs to it on every new interaction:
`POST <SLATE_WEBHOOK_URL>` with body
`{"type":"interactions","slateId":"home","interactions":[…]}` and header
`X-Slate-Signature: sha256=<hex hmac-sha256(body, SLATE_WEBHOOK_SECRET)>`.
On wake: run `slate.sh responses` (polling stays the source of truth —
webhooks are fire-and-forget with retries; never assume you saw them all).

## Idempotency & ordering

- **PUT is idempotent by content.** Re-pushing an identical spec bumps nothing
  visible: identical re-push → same `contentHash`, device re-fetch is a free
  304. Safe to retry pushes after timeouts.
- **Interactions dedupe on `seq`** (device-generated). You never send them —
  but if you replay responses locally, dedupe on their `id`, and never reuse a
  `seq` you have seen.
- **Todo `checked` is server-owned.** User toggles mutate the stored spec.
  Before re-pushing a slate that contains a todoList, `get` it first and carry
  the current `checked` values forward, or you will visually undo the user.

## Worked scenario — nightly backup decision

> 23:00 cron fires. No human present.
> 1. Push the nightly board (Loop A): services, backup **failed** (disk full), bedtime todos.
> 2. Disk needs a decision → also push a question to `home`
>    (envelope-level `"expiresAt": "<tomorrow 09:00>"` — it sits on the slate,
>    NOT inside the question element; late answers still arrive, flagged `stale`):
>    `"prompt": "Backup disk is full (412 GB). Purge old snapshots?"`,
>    options: `purge` (primary) / `skip` / `later`, `allowText: true`.
> 3. End. The cron session does NOT wait.
> 4. 23:17 the user taps **Purge** on the widget. Interaction recorded:
>    `kind: answer, questionId: q-backup, optionId: purge, optionLabel: Purge`.
> 5. 23:20 follow-up cron (or webhook wake): `responses home <cursor>` →
>    purge answer. Ack. Run the purge job.
> 6. Close the loop: re-push `home` — replace the question with
>    `{ "type": "statusRow", "icon": "💾", "label": "Backup", "value": "purged 58 GB", "tone": "ok" }`
>    and set surface `tone` back to `ok`. Same slate id, same element ids where
>    the rows persist. The user glances later and sees the board is green.

That final step — question becomes a status row — is what makes the loop feel
closed instead of dropped. Always do it.

## Bundled examples

`examples/` holds synced copies of the contract's golden payloads:
`status-board.json` (Loop A), `question.json` (Loop B), `minimal.json`.
Validate and push them as-is to smoke-test your setup:

```bash
slate.sh validate examples/status-board.json && slate.sh push examples/status-board.json home
```
