# Runnable examples

First-class examples per the convention: each file here is a complete, valid
slate you can push with one command (backend on `localhost:3000`, key in
`SLATE_API_KEY`):

```bash
curl -X PUT "$SLATE_URL/api/slates/home" \
  -H "Authorization: Bearer $SLATE_API_KEY" \
  -H "Content-Type: application/json" \
  -d @examples/agent-setup/home-board.json
```

| File | What it demonstrates |
|---|---|
| `agent-setup/home-board.json` | Smallest useful board — the one the docs site's setup flow pushes |
| `agent-setup/nightly-board.json` | Multi-row status board with tones + a todo list |
| `agent-setup/decision-question.json` | The Loop-B question: canned answers + free text |

The same payloads live in the [documentation site](https://nicklewanowicz.github.io/slate/) under Examples.
