# Contributing to Slate

Thanks for helping make Slate better! Slate is a small, opinionated product:
a declarative home-screen widget for AI agents. Contributions that grow the
element vocabulary or add enterprise features will be weighed against the
project's core value — **simple when it needs to be, complex when it has to be**.

## Development

```bash
# Backend (Bun + Elysia + SQLite)
cd backend && bun install && bun run dev          # http://localhost:3000
bun run test                                      # 100+ tests, in-memory SQLite
bun run coverage                                  # gate: >=80% branch

# Android (Kotlin, minSdk 31)
cd android
JAVA_HOME=<jdk-17> ./gradlew :app:testDebugUnitTest :app:koverVerify :app:assembleDebug
```

Both coverage gates are enforced — changes must keep branch coverage >= 80%.

## The contract is the product

- `schema/slate.schema.json`, `schema/interactions.schema.json` and `schema/api.md`
  are the frozen contract between agents, backend and devices.
- Backend and Android changes must keep the schemas and `schema/golden/*.json`
  passing.
- The renderer is lenient by design: unknown content degrades to a caption on
  devices; the backend rejects it with an actionable `hint` at push time.

## Pull requests

1. Keep PRs focused; one behavior or fix per PR.
2. Add tests that would catch the regression you're fixing.
3. Update docs (`docs/`) in the same PR when behavior or the contract changes.
4. Error messages matter: every error path needs a non-empty `hint` written for
   an LLM (valid values, closest guess, corrected snippet).

## Reporting bugs

Include: what you pushed or tapped, the full JSON error envelope (the `hint`
helps), backend version (`GET /api/ping`), and device/Android version.

## Security

See [SECURITY.md](SECURITY.md). Slate is designed for trusted home networks —
please don't file public issues for anything that depends on an attacker
already being inside your LAN.
