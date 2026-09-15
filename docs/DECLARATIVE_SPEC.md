# Writing slates — the declarative spec, complete reference

The canonical reference for [`schema/slate.schema.json`](../schema/slate.schema.json):
everything an agent or human needs to author any valid slate without opening the
schema. For wire-level endpoints see [`schema/api.md`](../schema/api.md); for
copy-paste curl see [`API_EXAMPLES.md`](API_EXAMPLES.md).

Design rule the schema enforces: **semantic, never visual.** You choose tones and
styles; renderers decide colors and layout. Unknown content is handled in two
deliberately different ways — keep them straight:

- **Renderers degrade.** On a device, an element type from a future spec renders
  as a caption (`[unsupported: carousel]`) and an unknown tone falls back to
  `neutral` — forward compatibility, never a crash.
- **The backend rejects.** At push time, an unknown element type, unknown
  property, unknown tone, or `version` ≠ 2 is a **400 with an LLM-friendly
  hint** — on purpose, so agents catch typos immediately instead of shipping
  content that silently renders as a caption.

Pushes are **full replaces**; there is no PATCH — re-PUT the whole envelope every time.

## The envelope

```json
{
  "id": "envelope-demo",
  "version": 2,
  "title": "🌙 Nightly",
  "tone": "warn",
  "expiresAt": "2026-09-15T09:00:00Z",
  "children": [
    { "type": "text", "text": "Before bed", "style": "heading" }
  ]
}
```

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `id` | slug | yes | — | `^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,39}$` — 1–40 chars, starts alphanumeric. Must match the `PUT /api/slates/{id}` path. Stable across pushes: todos/answers key on it. |
| `version` | const 2 | yes | — | Any other value → 400. |
| `title` | string ≤60 | no | `""` | Header line. |
| `tone` | tone | no | `neutral` | Sets the *baseline*; the header accent renders the **aggregate** (see Tones). |
| `updatedAt` | ISO-8601 ≤32 | no | server-stamped | **Never send it.** Server stamps on PUT if omitted. If you send one it is stored as-is (it is schema-legal) and will mislead freshness displays; the `contentHash` ignores it. |
| `expiresAt` | ISO-8601 ≤32 | no | — | Surface-level staleness, not deletion: header appends `· stale` and the freshness dot greys; interactions are still recorded but flagged `stale: true` in `/responses` once the instant has passed. |
| `children` | element[] | yes | — | Root body. |

## Global caps

| Cap | Value | Enforced by | Violation |
|---|---|---|---|
| Children per container | 12 (`maxItems`) | JSON schema | 400 `validation_error` |
| Serialized size | 64 KB | backend | 400, code `payload_too_large` (not 413) |
| Depth | 6 | **enforced (400 caps_exceeded)** | keep ≤3; real boards top out at 2 |
| Total elements | 60 | **enforced (400 caps_exceeded)** | containers count toward the total |

Depth/total are stated in the schema description and MVP spec but no schema rule
or backend check enforces them (verified against `backend/src/`); the 64 KB and
per-container caps are what actually stop runaway payloads.

## Tones (the only colors you get)

| tone | Meaning | Typical use |
|---|---|---|
| `ok` | green | healthy, done, passed |
| `warn` | amber | needs attention soon, orange-ish emphasis |
| `error` | red | failed, destructive option |
| `info` | blue | informational highlight |
| `neutral` | grey (default) | everything else |

**Where tone applies** (Android widget, verified in code): the header accent bar
renders the **aggregate tone** — the most severe of the surface tone plus every
descendant `tone` (severity: `error` > `warn` > `info` > `ok` > `neutral`); the
freshness dot (same aggregate, grey when expired); `text` color; `statusRow`
icon-accent, value and accent bar; `progress` fill; `todoList` item label + row
accent; the `primary` question option's fill. Neutral values/text render in the
theme's secondary color, never a tone.

**Anti-pattern rule:** there are no hex colors, no font names, no images, no
`fontSize` — `additionalProperties: false` rejects them. `statusRow.icon` is an
emoji or single glyph ≤4 chars (`"🏠"`, `"•"`), never a font or drawable name.

## Element reference

Nine element types. Every element takes an optional `id` (slug) **except
`todoList` and `question`, where it is required** — interactions echo it back.
Passive elements (render-only): `column`, `row`, `text`, `statusRow`, `progress`,
`divider`, `spacer`. Interactive: `todoList` (items toggle), `question` (options
tap, free-text reply row). The widget header deep-links to the app detail screen.

Prop columns: **Req** = required · limits are schema-enforced (a violation is a
400, never a silent clip) · defaults apply when the prop is omitted.

### `column` — vertical group
```json
{ "type": "column", "gap": 12, "children": [
  { "type": "text", "text": "First" }, { "type": "text", "text": "Second" } ] }
```
| Prop | Type | Req | Default | Limits | Notes |
|---|---|---|---|---|---|
| `children` | element[] | yes | — | ≤12 | nested elements |
| `gap` | integer | no | 8 | 0–24 | dp between children |
| `padding` | integer | no | 0 | 0–24 | dp; applies to nested columns. Root-level: ignored — the widget root has a fixed 12dp padding (schema description says 16; code says 12) |
| `align` | `start`/`center`/`end` | no | `start` | — | horizontal gravity of children |

### `row` — horizontal group
```json
{ "type": "row", "gravity": "spaceBetween", "children": [
  { "type": "text", "text": "Left" }, { "type": "text", "text": "23 min", "tone": "warn" } ] }
```
| Prop | Type | Req | Default | Limits | Notes |
|---|---|---|---|---|---|
| `children` | element[] | no | `[]` | ≤12 | empty row is legal |
| `gap` | integer | no | 8 | 0–24 | dp between children |
| `gravity` | `start`/`center`/`end`/`spaceBetween` | no | `start` | — | `start`: the **last text child stretches + ellipsizes** (label/value pattern); `spaceBetween`: children pushed to the edges; `center`/`end`: container gravity |

### `text` — prose
```json
{ "type": "text", "text": "Deploy failed 3× on main", "style": "caption", "tone": "error" }
```
| Prop | Type | Req | Default | Limits | Notes |
|---|---|---|---|---|---|
| `text` | string | yes | — | ≤500 chars | |
| `style` | `title`/`heading`/`body`/`caption` | no | `body` | — | widget: title 20sp bold 1 line · heading 16sp 2 lines · body 14sp 3 lines · caption 12sp 1 line (caption renders secondary color when neutral) |
| `tone` | tone | no | `neutral` | — | colors the text |
| `maxLines` | integer | no | by style | 1–10 | title/caption 1, heading 2, body 3 |

### `statusRow` — THE status line
```json
{ "type": "statusRow", "icon": "💾", "label": "Backup", "value": "failed",
  "tone": "error", "detail": "disk full — 412 GB used" }
```
| Prop | Type | Req | Default | Limits | Notes |
|---|---|---|---|---|---|
| `icon` | string | no | `"•"` | ≤4 chars | emoji or single glyph — never a font/drawable name |
| `label` | string | yes | — | ≤80 chars | left side, 13sp, ellipsized |
| `value` | string | no | — | ≤80 chars | right-aligned, tone-colored ("3 unread") |
| `tone` | tone | no | `neutral` | — | colors the 3dp accent bar, icon, and value |
| `detail` | string | no | — | ≤160 chars | second caption line, 11sp ("checked 2m ago") |

### `todoList` — checkable list (interactive)
```json
{ "type": "todoList", "id": "bed", "items": [
  { "id": "doors", "label": "Lock doors" },
  { "id": "garage", "label": "Close garage", "checked": true } ] }
```
| Prop | Type | Req | Default | Limits | Notes |
|---|---|---|---|---|---|
| `id` | slug | **yes** | — | 1–40 chars | echoed as `elementId` in `check`/`uncheck` events |
| `items` | item[] | yes | — | 1–12 items | each item: `id` (slug, required), `label` ≤120 (required), `checked` bool (default false), `tone` (default neutral) |

Fixed 44dp rows with `☐`/`☑` glyph; `checked` renders the glyph in `ok` color;
item `tone` colors label + row accent. **Server owns `checked`**: toggles mutate
the stored spec server-side. Before re-pushing, GET the slate and carry the
returned `checked` forward — a blind re-push reverts the user's checks. (Device
pulls reconcile, but don't fight it.)

### `question` — ask + one-tap answer (interactive)
```json
{ "type": "question", "id": "q-fail", "prompt": "Deploy is failing. Retry?",
  "options": [
    { "id": "retry", "label": "Retry", "style": "primary" },
    { "id": "skip", "label": "Skip" } ],
  "allowText": true }
```
| Prop | Type | Req | Default | Limits | Notes |
|---|---|---|---|---|---|
| `id` | slug | **yes** | — | 1–40 chars | echoed as `questionId` in `answer`/`text` events |
| `prompt` | string | yes | — | ≤300 chars | rendered as a heading |
| `options` | option[] | yes | — | 2–6 | each: `id` (slug, required), `label` ≤40 (required), `style`: `primary`/`danger`/`quiet` (default `quiet`) |
| `allowText` | bool | no | false | — | adds a free-text reply affordance; **typing happens in the app** — the widget shows a "💬 Reply in app" row that deep-links |
| `placeholder` | string | no | `"Type a reply…"` | ≤80 chars | **app-only** (input field hint); the widget never shows it |

Widget rendering: options are laid out **2 per row**, and only the first
**4** render in the full bucket (**2** in compact/medium) — order matters, lead
with the likely answer. `primary` = filled accent, `danger` = filled red,
`quiet` = outlined. After an answer: the chosen option shows `✓ <label>` in `ok`
color; the others fade to 40% opacity and stop tapping.

### `progress` — percent or busy
```json
{ "type": "progress", "label": "Backup — 412 of 512 GB", "value": 80, "tone": "warn" }
```
| Prop | Type | Req | Default | Limits | Notes |
|---|---|---|---|---|---|
| `value` | number | only if not `indeterminate` | — | 0–100 | percent; a missing value renders as indeterminate |
| `indeterminate` | bool | no | false | — | busy spinner |
| `label` | string | no | — | ≤80 chars | hidden when blank |
| `tone` | tone | no | `neutral` | — | fill tint |

### `divider` — separator
```json
{ "type": "divider", "inset": true }
```
| Prop | Type | Req | Default | Limits | Notes |
|---|---|---|---|---|---|
| `inset` | bool | no | false | — | inset renders a 200dp-wide line; full-width otherwise |

### `spacer` — vertical gap
```json
{ "type": "spacer", "height": 16 }
```
| Prop | Type | Req | Default | Limits | Notes |
|---|---|---|---|---|---|
| `height` | integer | no | 8 | 2–48 | dp |

## Interaction contract

What each tappable thing sends (POST `/api/device/interactions`, batched ≤50,
all-or-nothing; `202 {accepted, duplicate}`):

| Tap target | `kind` | Fields carried |
|---|---|---|
| Question option (widget or app) | `answer` | `slateId`, `questionId`, `optionId`, **`optionLabel`** (label echo — the agent needs no lookup), `elementId` = question id |
| Todo row (widget or app) | `check` / `uncheck` | `slateId`, `itemId`, `elementId` = todoList id |
| `allowText` reply (in app) | `text` | `slateId`, `questionId`, `value` (≤1000 chars, the typed string) |
| — reserved — | `tap` | in the schema enum; nothing sends it today |

Every interaction also carries `seq` (client uuid, ≤64 — the idempotency key;
server dedupes on it, never reuse after a 2xx) and `clientAt` (on-device time).
The server applies `check`/`uncheck` to the stored spec (canonical `checked`)
and fires the webhook wake on every non-duplicate insert.

**Expired questions still tap.** The widget greys the header and adds `· stale`
but does not block option taps; the backend flags any interaction recorded after
`expiresAt` with `stale: true` in `/api/slates/{id}/responses`. Read `stale`
before acting on an answer to a deadline question.

## Question design guide

- **2–6 options**, but the widget shows at most 4 (full) / 2 (compact). Order by
  likelihood; never bury the recommended action at position 5.
- **One `primary` per question** — the recommended action. `danger` only for
  destructive/irreversible choices ("Mute this sensor", "Never ask again") —
  it renders filled red. Everything else `quiet`.
- **Write prompts that one tap fully answers.** Put context in a `text` element
  *before* the question ("deploy.yml failed 3× on main (401 on registry push).")
  and keep the prompt a decision: "I noticed the deploy job failing. What should
  I do?" — not background prose. ≤300 chars.
- **`allowText`** when you can't predict the answer; the widget shows a
  reply-in-app row, the app hosts the input. `placeholder` shows only in the app —
  make it an example ("e.g. rotate the token first").
- **`expiresAt`** when the decision window closes (sensor alarm until 9am,
  deploy gate until cut time). After it, answers still arrive but arrive flagged
  `stale` — check the flag before acting.
- **One question per slate.** Two questions on one screen halves the tap rate on each.

## Versioning & errors

`version` is `const 2`. Evolution policy for v2: **additive only** — new optional
props or tones ride along; nothing existing changes meaning. Never invent
element types, props, or tones "for later": the backend rejects them today (the
device renderer would degrade them, but you'll never get that far). Discover the
server's version via `GET /api/ping` → `{"ok":true,"serverTime":…,"version":"2.0.0"}`.
The interactions path is **strictly frozen**: an unknown `kind` is a hard 400,
no renderer fallback anywhere.

Every error is the same envelope (`code`, `message`, `hint`, `status`); `hint`
names the offending path, lists valid values, guesses your intent, and shows a
corrected snippet. Captured verbatim from the live backend:

Unknown element type:
```json
{"error":{"code":"validation_error","message":"children[1].type: unknown element type \"carousel\" (and 58 more issues)","hint":"\"carousel\" is not a valid element type at children[1]. Valid element types: column, row, text, statusRow, todoList, question, progress, divider, spacer. Closest valid type: \"column\". Renderers degrade unknown content to a caption, but the backend rejects it so agents catch typos early. Corrected snippet: {\"type\":\"column\", ...}","status":400}}
```

Unknown tone:
```json
{"error":{"code":"validation_error","message":"children[0].tone: must be one of ok|warn|error|info|neutral","hint":"children[0].tone must be one of \"ok\" | \"warn\" | \"error\" | \"info\" | \"neutral\". For \"chartreuse\" you probably want \"warn\". Corrected snippet: {\"tone\":\"warn\"}","status":400}}
```

Unknown property (visual-prop anti-pattern caught here):
```json
{"error":{"code":"validation_error","message":"children[0]: unknown property \"badge\"","hint":"Property \"badge\" is not allowed at children[0]. Allowed properties here: id, icon, label, value, tone, detail. Unknown fields are rejected to catch agent typos early. Corrected snippet: {\"type\":\"statusRow\",\"icon\":\"💾\",\"label\":\"Backup\"}","status":400}}
```

Wrong version:
```json
{"error":{"code":"validation_error","message":"version: must be 2","hint":"version must be exactly 2. Received: 3. Corrected snippet: {\"version\":2}. This server speaks spec version 2 only — check what versions it supports via GET /api/ping (field \"version\").","status":400}}
```

Unknown interaction kind (frozen contract):
```json
{"error":{"code":"validation_error","message":"interactions[0].kind: must be one of answer|check|uncheck|text|tap","hint":"interactions[0].kind must be one of \"answer\" | \"check\" | \"uncheck\" | \"text\" | \"tap\". For \"share\" you probably want \"check\". Corrected snippet: {\"kind\":\"check\"}","status":400}}
```

The golden [`unknown-element.json`](../schema/golden/unknown-element.json) is
this failure as a fixture: valid today only as a **renderer** test — as a push
it 400s on the `carousel` type (and would also reject the `chartreuse` tone).

## Size buckets (Android widget)

Bucket comes from the widget's min width (`SizeBucket.fromWidthDp`);
`WidgetPlanner` picks which elements render. What the code does today:

| Bucket | min width | What renders |
|---|---|---|
| compact | ≤250dp | title + freshness + up to **3 statusRows** (digest); if the slate has none, the **first 2 elements** |
| medium | ≤400dp | title + freshness + **all statusRows + the first question** (compact: 2 options); fallback to first 2 elements if neither exists — progress/todoList/text do *not* render here otherwise |
| full | >400dp | everything. The **first `todoList`** becomes the fixed-height ListView (44dp rows, visible rows capped at 6); later todoLists render inline, also capped at 6 rows. Questions render up to 4 options |

Always rendered: accent bar (28×3dp, aggregate tone) · title (falls back to the
slate id when blank) · freshness dot + relative label (`updated 6m ago`), with
`· stale` appended when `expiresAt` has passed. An empty body renders the
"All quiet." empty state, not a void.

## Examples gallery

The six golden fixtures from [`schema/golden/`](../schema/golden/) plus three QA
scenarios. All validate against the schema except `unknown-element.json`
(deliberate failure demo).

**`minimal.json` — the floor.** One text child; proves a slate needs nothing else.

```json
{
  "id": "meds",
  "version": 2,
  "title": "Evening",
  "children": [
    { "type": "text", "text": "💊 Time to take meds — tap to open and log it." }
  ]
}
```
Renders: header "Evening" + neutral accent bar, one body-style line. Fits every bucket.

**`status-board.json` — the status digest.** Rows, a divider, todos for the physical world.

```json
{
  "id": "nightly", "version": 2, "title": "🌙 Nightly", "tone": "warn",
  "children": [
    { "type": "statusRow", "icon": "🏠", "label": "Home Assistant", "value": "ok", "tone": "ok", "detail": "checked 2m ago" },
    { "type": "statusRow", "icon": "📧", "label": "Email", "value": "3 unread", "tone": "info" },
    { "type": "statusRow", "icon": "🔒", "label": "Security", "value": "armed", "tone": "ok" },
    { "type": "statusRow", "icon": "💾", "label": "Backup", "value": "failed", "tone": "error", "detail": "disk full — 412 GB used" },
    { "type": "divider" },
    { "type": "text", "text": "Before bed", "style": "heading" },
    { "type": "todoList", "id": "bed", "items": [
      { "id": "doors", "label": "Lock doors", "checked": true },
      { "id": "garage", "label": "Close garage" }
    ]}
  ]
}
```
Renders: the Backup row's red accent bar and tone-colored "failed" value (rows are
wrap-height, 13sp, with the 11sp `detail` caption); the aggregate header tone is
**error** — the failed Backup row out-ranks the `warn` surface tone; the `bed`
todoList becomes the 44dp-row ListView in the full bucket.

**`question.json` — the question loop.** Context first, one `primary`, `danger` last.

```json
{
  "id": "job-alert",
  "version": 2,
  "title": "⚠️ CI needs you",
  "tone": "error",
  "children": [
    { "type": "text", "text": "deploy.yml has failed 3× on main (last: 401 on registry push).", "maxLines": 2 },
    { "type": "question", "id": "q-fail", "prompt": "I noticed the deploy job failing. What should I do?",
      "options": [
        { "id": "retry", "label": "Retry", "style": "primary" },
        { "id": "skip", "label": "Skip this deploy" },
        { "id": "more", "label": "Tell me more" },
        { "id": "mute", "label": "Never ask again", "style": "danger" }
      ],
      "allowText": true,
      "placeholder": "e.g. rotate the token first" }
  ]
}
```
Renders: red header accent (`error`); options 2-per-row — Retry filled accent,
mute filled red, the rest outlined; a "💬 Reply in app" row (the placeholder
never shows on the widget). After tapping, Retry shows "✓ Retry" and the rest fade.

**`dashboard.json` — mixed morning brief.** Rows, progress, todos in one screen.

```json
{
  "id": "dash",
  "version": 2,
  "title": "🌤 Morning brief",
  "tone": "info",
  "children": [
    { "type": "row", "gravity": "start", "children": [
      { "type": "text", "text": "18° · Partly cloudy · High 23°", "style": "heading" } ] },
    { "type": "progress", "label": "Commute traffic — light", "value": 22, "tone": "ok" },
    { "type": "divider" },
    { "type": "statusRow", "icon": "📅", "label": "Next up", "value": "Standup 9:15", "tone": "info", "detail": "3 events today" },
    { "type": "statusRow", "icon": "💸", "label": "Budget", "value": "78% used", "tone": "warn" },
    { "type": "progress", "label": "Budget burn", "value": 78, "tone": "warn" },
    { "type": "divider" },
    { "type": "text", "text": "Today's focus", "style": "heading" },
    { "type": "todoList", "id": "focus", "items": [
      { "id": "review", "label": "Review Slate v2 spec", "checked": true },
      { "id": "call", "label": "Call plumber", "tone": "warn" },
      { "id": "gym", "label": "Gym before 18:00" }
    ] }
  ]
}
```
Renders: two progress bars (22% green, 78% amber); aggregate header tone **warn**
(the warn rows/items out-rank `info`); in the full bucket the `focus` list is the
ListView (3 × 44dp rows, "Call plumber" in amber); in medium only the two
statusRows + freshness show.

**`edge-cases.json` — every style, tone, and truncation stress.**

```json
{
  "id": "edge",
  "version": 2,
  "title": "Edge cases: long strings, every tone, all styles",
  "tone": "info",
  "expiresAt": "2026-12-31T23:59:59Z",
  "children": [
    { "type": "text", "text": "title style: the quick brown fox jumps over the lazy dog and keeps going well past a single line to prove truncation", "style": "title" },
    { "type": "spacer", "height": 16 },

    { "type": "text", "text": "ok tone text", "tone": "ok" },
    { "type": "text", "text": "warn tone text", "tone": "warn" },
    { "type": "text", "text": "error tone text", "tone": "error" },
    { "type": "text", "text": "info tone text", "tone": "info" },
    { "type": "divider", "inset": true },
    { "type": "row", "gravity": "spaceBetween", "children": [
      { "type": "text", "text": "left" },
      { "type": "text", "text": "right", "tone": "info" } ] },
    { "type": "progress", "indeterminate": true, "label": "Working…" },
    { "type": "progress", "value": 100, "label": "Full", "tone": "ok" },
    { "type": "todoList", "id": "edge-todo", "items": [
      { "id": "a", "label": "Unchecked item with a fairly long label that should ellipsize gracefully on narrow widget sizes" },
      { "id": "b", "label": "Checked item", "checked": true },
      { "id": "c", "label": "Warn item", "tone": "warn" }
    ] },
    { "type": "question", "id": "edge-q", "prompt": "Six options, all styles, no text allowed",
      "options": [
        { "id": "o1", "label": "Primary", "style": "primary" },
        { "id": "o2", "label": "Danger", "style": "danger" },
        { "id": "o3", "label": "Quiet one" },
        { "id": "o4", "label": "Quiet two" },
        { "id": "o5", "label": "Quiet three" },
        { "id": "o6", "label": "Quiet four" }
      ] }
  ]
}
```
Renders: the title truncates to 1 line at 20sp bold; aggregate header tone
**error** (an `error` text exists); the spaceBetween row pins "right" to the far
edge; the six-option question shows only its **first 4** in the full bucket —
`o5`/`o6` are dropped by the renderer, a key reason to keep options ≤4.

**`unknown-element.json` — forward-compat failure demo (deliberate 400).**

```json
{
  "id": "future",
  "version": 2,
  "title": "Forward compatibility",
  "children": [
    { "type": "statusRow", "icon": "✅", "label": "Known elements still render", "value": "ok", "tone": "ok" },
    { "type": "carousel", "items": [{ "title": "Something new in spec v3" }] },
    { "type": "text", "text": "Unknown elements above must degrade to a caption, never crash.", "style": "caption" },
    { "type": "text", "text": "Unknown tone below must fall back to neutral.", "tone": "chartreuse" }
  ]
}
```
Not valid to push (see the captured 400 above). It exists for **renderer**
tests: a device that understood `carousel` would render `[unsupported: carousel]`
as a caption and treat `chartreuse` as `neutral` — today's backend stops the
payload before any renderer sees it.

**`security-question.json` (QA) — the alarm loop.**

```json
{
  "id": "alerts",
  "version": 2,
  "title": "🔒 Security",
  "tone": "error",
  "children": [
    { "type": "statusRow", "icon": "🚪", "label": "Back door", "value": "open 23 min", "tone": "error", "detail": "sensor: backdoor/motorola-1" },
    { "type": "statusRow", "icon": "📹", "label": "Cameras", "value": "all recording", "tone": "ok" },
    { "type": "question", "id": "q-backdoor",
      "prompt": "Back door has been open for 23 minutes and nobody is home. Announce it, or wait?",
      "options": [
        { "id": "announce", "label": "Announce indoors", "style": "primary" },
        { "id": "flash", "label": "Flash lights" },
        { "id": "ignore", "label": "Ignore" },
        { "id": "mute-sensor", "label": "Mute this sensor", "style": "danger" }
      ],
      "allowText": true }
  ],
  "expiresAt": "2026-09-15T09:00:00Z"
}
```
Renders: red header accent; the back-door row's value "open 23 min" in red;
primary/danger styling as above. After 09:00Z the header shows `· stale` and any
late answer arrives with `stale: true`.

**`media-server.json` (QA) — progress without questions.**

```json
{
  "id": "media", "version": 2, "title": "🎬 Media server", "tone": "warn",
  "children": [
    { "type": "statusRow", "icon": "🍿", "label": "Jellyfin", "value": "3 streaming", "tone": "ok", "detail": "peak 22:40, CPU 41%" },
    { "type": "statusRow", "icon": "⬇️", "label": "Downloads queue", "value": "17 pending", "tone": "warn", "detail": "2 failed — indexers timed out" },
    { "type": "statusRow", "icon": "💽", "label": "Storage pool", "value": "81% used", "tone": "warn" },
    { "type": "progress", "label": "Plex → Jellyfin migration", "value": 64, "tone": "info" }
  ]
}
```
Renders: four wrap-height status rows + a 64% blue-tinted bar; aggregate header
tone **warn**; in medium, the three statusRows render and the progress bar does not.

**`groceries.json` (QA) — a todoList-first slate.**

```json
{
  "id": "groceries", "version": 2, "title": "🛒 Groceries", "children": [
    { "type": "todoList", "id": "list", "items": [
      { "id": "milk", "label": "Milk" },
      { "id": "eggs", "label": "Eggs", "checked": true },
      { "id": "coffee", "label": "Coffee beans", "tone": "warn" },
      { "id": "basil", "label": "Basil plant" }
    ]}
  ]
}
```
Renders: neutral surface; aggregate tone **warn** (the coffee item); full bucket:
ListView 4 × 44dp rows with `☑ Eggs` green-checked; compact: no statusRows, so
the first-2 fallback shows the list inline (up to 6 rows).

## Anti-patterns (all rejected or wrong)

- **Raw colors / visual props.** `"color": "#ff8800"`, `"fontSize": 20`,
  background images — rejected 400 (`additionalProperties: false`). Use `tone`
  and `style`; the icon slot takes emoji/glyphs only, never font names.
- **Deep nesting.** Depth 6 and 60 elements are hard caps (400 `caps_exceeded`); if you need a tree,
  you need two slates. Real boards top out at depth 2.
- **Unstable ids.** `id: "todo-1726344000"` breaks user state — todo checks and
  answer history key on element ids. Same logical element ⇒ same slug forever.
- **Typo'd types/tones/props.** All 400 with a hint naming the path (see
  Versioning & errors). Don't rely on renderer degradation — the push fails first.
- **Text walls.** `text` caps at 500 chars, renders ≤3 lines by default. Prose
  belongs in agent chat; the surface is glanceable.
- **Question spam.** One question per slate; ≤4 options if you want them all visible.
- **Re-sending `updatedAt`,** or **re-pushing todos blind.** The server stamps
  `updatedAt` and owns `checked` — GET first, carry `checked` forward.

Validate before you push (`skill/slate/scripts/slate.sh validate FILE`) — it
applies the same schema the backend uses, offline.
