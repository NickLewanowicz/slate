# Security Policy

## Design posture

Slate is built for **trusted home networks**. One shared API key, no accounts,
no TLS termination (front it with a reverse proxy if you expose it). If that
model doesn't match your threat model, Slate isn't the right tool — and that's
okay.

What the project guarantees (verified by test):

- API keys are compared in constant time; error responses never leak key or
  slate existence pre-authentication.
- All SQL is parameterized; no string-built queries.
- Destructive operations (ack/delete) are strictly slate-scoped.
- Structural caps (depth ≤ 6, 60 elements, 64 KB / 256 KB limits) are enforced
  before schema validation, so hostile payloads cannot wedge the server.
- Webhook deliveries are HMAC-signed (`X-Slate-Signature`) when a secret is set.
- Interaction replays are deduplicated by `seq`, per slate.

## Reporting a vulnerability

Open a GitHub security advisory ("Report a vulnerability" on the Security tab)
rather than a public issue. Please include a minimal reproduction. Homelab
deployment hardening tips live in the docs site; hardening requests that assume
a hostile LAN are out of scope by design (see BACKLOG for multi-key auth).
