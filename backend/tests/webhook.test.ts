import { describe, expect, test } from "bun:test";
import { hmacSha256Hex } from "../src/canonical";
import { answer, jsonHeaders, postInteractions, putSlate, readGolden, withHarness } from "./helpers";

const dashboard = () => readGolden("dashboard.json");

describe("webhook wake", () => {
  test("no SLATE_WEBHOOK_URL -> no HTTP call", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", dashboard());
      await postInteractions(h, [answer("s1", "dash", "edge-q", "o1")]);
      await h.app.webhook.idle();
      expect(h.webhookStub.calls).toEqual([]);
    });
  });

  test("fires once per batch with response-shaped payload", async () => {
    await withHarness(
      async (h) => {
        expect(h.config.webhookUrl).toBeDefined();
        await putSlate(h, "dash", dashboard());
        await postInteractions(h, [
          answer("s1", "dash", "edge-q", "o1", "Primary"),
          { seq: "s2", slateId: "dash", kind: "check", elementId: "focus", itemId: "call" },
        ]);
        await h.app.webhook.idle();
        expect(h.webhookStub.calls.length).toBe(1);
        const call = h.webhookStub.calls[0];
        expect(call.url).toBe(h.config.webhookUrl!);
        expect(call.method).toBe("POST");
        expect(call.headers.get("content-type")).toBe("application/json");

        const payload = JSON.parse(call.body) as {
          type: string;
          slateId: string;
          interactions: Array<Record<string, unknown>>;
        };
        expect(payload.type).toBe("interactions");
        expect(payload.slateId).toBe("dash");
        expect(payload.interactions.length).toBe(2);
        const first = payload.interactions[0];
        for (const field of ["id", "slateId", "kind", "elementId", "questionId", "optionId", "optionLabel", "itemId", "value", "clientAt", "stale", "createdAt"]) {
          expect(Object.keys(first)).toContain(field);
        }
        expect(Object.keys(first)).not.toContain("seq");
        expect(first["optionLabel"]).toBe("Primary");
        expect(first["stale"]).toBe(false);
      },
      { config: { webhookUrl: "http://wake.internal/hook" } },
    );
  });

  test("HMAC signature header is set when SLATE_WEBHOOK_SECRET exists", async () => {
    await withHarness(
      async (h) => {
        await putSlate(h, "dash", dashboard());
        await postInteractions(h, [answer("s1", "dash", "edge-q", "o1")]);
        await h.app.webhook.idle();
        const call = h.webhookStub.calls[0];
        const expected = `sha256=${hmacSha256Hex(h.config.webhookSecret!, call.body)}`;
        expect(call.headers.get("x-slate-signature")).toBe(expected);
      },
      { config: { webhookUrl: "http://wake.internal/hook", webhookSecret: "s3cret" } },
    );
  });

  test("no signature header when the secret is unset", async () => {
    await withHarness(
      async (h) => {
        await putSlate(h, "dash", dashboard());
        await postInteractions(h, [answer("s1", "dash", "edge-q", "o1")]);
        await h.app.webhook.idle();
        expect(h.webhookStub.calls[0].headers.get("x-slate-signature")).toBeNull();
      },
      { config: { webhookUrl: "http://wake.internal/hook" } },
    );
  });

  test("duplicate-only batch fires no webhook", async () => {
    await withHarness(
      async (h) => {
        await putSlate(h, "dash", dashboard());
        await postInteractions(h, [answer("s1", "dash", "edge-q", "o1")]);
        await h.app.webhook.idle();
        expect(h.webhookStub.calls.length).toBe(1);
        // replay
        await postInteractions(h, [answer("s1", "dash", "edge-q", "o1")]);
        await h.app.webhook.idle();
        expect(h.webhookStub.calls.length).toBe(1); // unchanged
      },
      { config: { webhookUrl: "http://wake.internal/hook" } },
    );
  });

  test("batch mixing two slates fires one webhook per slate", async () => {
    await withHarness(
      async (h) => {
        await putSlate(h, "a", minimalSpecA());
        await putSlate(h, "b", minimalSpecB());
        await postInteractions(h, [
          answer("s1", "a", "q", "x"),
          answer("s2", "b", "q", "y"),
        ]);
        await h.app.webhook.idle();
        expect(h.webhookStub.calls.length).toBe(2);
        const slates = h.webhookStub.calls.map((c) => (JSON.parse(c.body) as { slateId: string }).slateId).sort();
        expect(slates).toEqual(["a", "b"]);
      },
      { config: { webhookUrl: "http://wake.internal/hook" } },
    );
  });

  test("failures retry 3 times (2s/8s/30s schedule, shortened in tests) and never fail the 202", async () => {
    await withHarness(
      async (h) => {
        await putSlate(h, "dash", dashboard());
        h.webhookStub.behavior = 500;
        const r = await postInteractions(h, [answer("s1", "dash", "edge-q", "o1")]);
        expect(r.status).toBe(202); // webhook failure is invisible to the client
        await h.app.webhook.idle();
        expect(h.webhookStub.calls.length).toBe(4); // initial + 3 retries
        // rows are still readable via polling (source of truth)
        const list = (await (await h.get("/api/slates/dash/responses")).json()) as { responses: unknown[] };
        expect(list.responses.length).toBe(1);
      },
      { config: { webhookUrl: "http://wake.internal/hook" } },
    );
  });

  test("retries until success: first attempt 503, second 200", async () => {
    await withHarness(
      async (h) => {
        await putSlate(h, "dash", dashboard());
        h.webhookStub.statusQueue.push(503); // first attempt fails, then behavior "ok"
        await postInteractions(h, [answer("s1", "dash", "edge-q", "o1")]);
        await h.app.webhook.idle();
        expect(h.webhookStub.calls.length).toBe(2);
      },
      { config: { webhookUrl: "http://wake.internal/hook" } },
    );
  });

  test("network errors (fetch throws) are retried and never fail the 202", async () => {
    await withHarness(
      async (h) => {
        await putSlate(h, "dash", dashboard());
        h.webhookStub.behavior = "throw";
        const r = await postInteractions(h, [answer("s1", "dash", "edge-q", "o1")]);
        expect(r.status).toBe(202);
        await h.app.webhook.idle();
        expect(h.webhookStub.calls.length).toBe(4);
        expect(h.app.webhook.pendingCount()).toBe(0);
      },
      { config: { webhookUrl: "http://wake.internal/hook" } },
    );
  });

  test("webhook.stop() prevents further deliveries", async () => {
    await withHarness(
      async (h) => {
        await h.app.webhook.stop();
        await putSlate(h, "dash", dashboard());
        await postInteractions(h, [answer("s1", "dash", "edge-q", "o1")]);
        await h.app.webhook.idle();
        expect(h.webhookStub.calls.length).toBe(0);
      },
      { config: { webhookUrl: "http://wake.internal/hook" } },
    );
  });
});

function minimalSpecA(): Record<string, unknown> {
  return { id: "a", version: 2, children: [{ type: "question", id: "q", prompt: "?", options: [{ id: "x", label: "X" }, { id: "y", label: "Y" }] }] };
}
function minimalSpecB(): Record<string, unknown> {
  return { id: "b", version: 2, children: [{ type: "question", id: "q", prompt: "?", options: [{ id: "x", label: "X" }, { id: "y", label: "Y" }] }] };
}
