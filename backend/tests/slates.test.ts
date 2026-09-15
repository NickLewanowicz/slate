import { describe, expect, test } from "bun:test";
import { contentHashOf } from "../src/store";
import { canonicalJson } from "../src/canonical";
import { contentLengthTooLarge } from "../src/http";
import { expectErrorEnvelope, jsonHeaders, minimalSpec, putSlate, readGolden, withHarness } from "./helpers";

describe("slates CRUD", () => {
  test("PUT new returns 201 with created:true, server-stamped updatedAt and contentHash", async () => {
    await withHarness(async (h) => {
      const before = Date.now();
      const r = await putSlate(h, "home", minimalSpec());
      expect(r.status).toBe(201);
      const body = (await r.json()) as { slateId: string; updatedAt: string; contentHash: string; created: boolean };
      expect(body.slateId).toBe("home");
      expect(body.created).toBe(true);
      expect(Number.isFinite(Date.parse(body.updatedAt))).toBe(true);
      expect(Date.parse(body.updatedAt)).toBeGreaterThanOrEqual(before - 1000);
      expect(body.contentHash).toMatch(/^[0-9a-f]{64}$/);
      expect(body.contentHash).toBe(contentHashOf(minimalSpec()));
    });
  });

  test("identical re-push is idempotent: 200 created:false, SAME contentHash", async () => {
    await withHarness(async (h) => {
      const first = (await (await putSlate(h, "home", minimalSpec())).json()) as { updatedAt: string; contentHash: string; created: boolean };
      const second = (await (await putSlate(h, "home", minimalSpec())).json()) as typeof first;
      expect(second.created).toBe(false);
      expect(second.contentHash).toBe(first.contentHash);
      // updatedAt column refreshes on each push (server wall clock)
      expect(Date.parse(second.updatedAt)).toBeGreaterThanOrEqual(Date.parse(first.updatedAt));
    });
  });

  test("changed content produces a different contentHash", async () => {
    await withHarness(async (h) => {
      const first = (await (await putSlate(h, "home", minimalSpec())).json()) as { contentHash: string; created: boolean };
      const changed = (await (
        await putSlate(h, "home", minimalSpec({ title: "Changed" }))
      ).json()) as typeof first;
      expect(changed.created).toBe(false);
      expect(changed.contentHash).not.toBe(first.contentHash);
    });
  });

  test("path id must equal body id (400 with corrective hint)", async () => {
    await withHarness(async (h) => {
      const r = await putSlate(h, "home", minimalSpec({}, "other"));
      const err = await expectErrorEnvelope(r, 400);
      expect(err.code).toBe("id_mismatch");
      expect(err.hint).toContain('"home"');
      expect(err.hint).toContain('"other"');
    });
  });

  test("PUT invalid JSON returns invalid_json envelope", async () => {
    await withHarness(async (h) => {
      const r = await h.handle("/api/slates/home", { method: "PUT", headers: jsonHeaders(), body: "{not json" });
      const err = await expectErrorEnvelope(r, 400);
      expect(err.code).toBe("invalid_json");
    });
  });

  test("PUT empty body returns invalid_json envelope", async () => {
    await withHarness(async (h) => {
      const r = await h.handle("/api/slates/home", { method: "PUT", headers: jsonHeaders(), body: "" });
      const err = await expectErrorEnvelope(r, 400);
      expect(err.code).toBe("invalid_json");
    });
  });

  test("GET returns the stored envelope with server-stamped updatedAt applied", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "home", minimalSpec());
      const r = await h.get("/api/slates/home");
      expect(r.status).toBe(200);
      const envelope = (await r.json()) as Record<string, unknown>;
      expect(envelope["id"]).toBe("home");
      expect(envelope["version"]).toBe(2);
      expect(typeof envelope["updatedAt"]).toBe("string");
      expect((envelope["children"] as unknown[]).length).toBe(1);
    });
  });

  test("agent-supplied updatedAt is OVERWRITTEN — the server owns it (P8: stale stamps cannot poison sort)", async () => {
    await withHarness(async (h) => {
      const stale = "2020-01-15T09:30:00Z";
      await putSlate(h, "home", minimalSpec({ updatedAt: stale }));
      const envelope = (await (await h.get("/api/slates/home")).json()) as Record<string, unknown>;
      expect(envelope["updatedAt"]).not.toBe(stale);
      expect(typeof envelope["updatedAt"]).toBe("string");
      // The pushed stamp must not survive anywhere in the stored envelope.
      expect(JSON.stringify(envelope).includes(stale)).toBe(false);
    });
  });

  test("GET unknown slate 404 with helpful hint", async () => {
    await withHarness(async (h) => {
      const r = await h.get("/api/slates/nope");
      const err = await expectErrorEnvelope(r, 404);
      expect(err.hint).toContain("PUT /api/slates/nope");
    });
  });

  test("list returns all slates with summary fields", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "a", minimalSpec({ tone: "ok" }, "a"));
      await putSlate(h, "b", minimalSpec({ tone: "warn" }, "b"));
      const r = await h.get("/api/slates");
      expect(r.status).toBe(200);
      const body = (await r.json()) as { slates: Array<{ slateId: string; tone: string; updatedAt: string; contentHash: string }> };
      expect(body.slates.map((s) => s.slateId)).toEqual(["a", "b"]);
      for (const entry of body.slates) {
        expect(["ok", "warn"]).toContain(entry.tone);
        expect(entry.updatedAt.length).toBeGreaterThan(0);
        expect(entry.contentHash).toMatch(/^[0-9a-f]{64}$/);
      }
    });
  });

  test("DELETE returns 204, removes the slate, cascades interactions, then 404s", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "home", minimalSpec());
      await h.handle("/api/device/interactions", {
        method: "POST",
        headers: jsonHeaders(),
        body: JSON.stringify({ interactions: [{ seq: "s1", slateId: "home", kind: "tap", elementId: "el" }] }),
      });
      const r = await h.handle("/api/slates/home", { method: "DELETE" });
      expect(r.status).toBe(204);
      expect(await r.text()).toBe("");

      expect((await h.get("/api/slates/home")).status).toBe(404);
      expect((await h.get("/api/device/slates/home")).status).toBe(404);
      expect((await h.get("/api/slates/home/responses")).status).toBe(404);
      expect((await h.handle("/api/slates/home", { method: "DELETE" })).status).toBe(404);

      // Recreating the slate shows the cascade removed the interactions.
      await putSlate(h, "home", minimalSpec());
      const responses = (await (await h.get("/api/slates/home/responses")).json()) as { responses: unknown[] };
      expect(responses.responses).toEqual([]);
    });
  });

  test("contentHash ignores server metadata and matches canonical sha256", async () => {
    await withHarness(async (h) => {
      const spec = minimalSpec({ children: [{ type: "text", text: "a" }, { id: "s", type: "spacer", height: 8 }] });
      const put = (await (await putSlate(h, "home", spec)).json()) as { contentHash: string };
      const envelope = { ...spec, updatedAt: "2026-01-01T00:00:00Z" };
      expect(put.contentHash).toBe(contentHashOf(envelope));
      expect(put.contentHash).toBe(contentHashOf({ ...envelope, updatedAt: "2027-01-01T00:00:00Z" }));
      // canonical JSON equivalence: different key order, same hash
      const reordered = JSON.parse(JSON.stringify({ version: 2, id: "home", title: "Hello", children: [{ text: "a", type: "text" }, { type: "spacer", height: 8, id: "s" }] }));
      const put2 = (await (await putSlate(h, "home", reordered)).json()) as { contentHash: string };
      expect(put2.contentHash).toBe(put.contentHash);
      expect(canonicalJson(reordered)).toBe(canonicalJson(spec));
    });
  });

  test("all golden fixtures validate and store (except unknown-element, tested separately)", async () => {
    await withHarness(async (h) => {
      const names = ["minimal.json", "question.json", "dashboard.json", "status-board.json", "edge-cases.json"];
      for (const name of names) {
        const spec = readGolden(name);
        const r = await putSlate(h, spec["id"] as string, spec);
        expect(r.status, `${name} should validate`).toBe(201);
      }
      const list = (await (await h.get("/api/slates")).json()) as { slates: Array<{ slateId: string }> };
      expect(list.slates.map((s) => s.slateId).sort()).toEqual(["dash", "edge", "job-alert", "meds", "nightly"]);
    });
  });

  // ---- structural caps (P5 security finding: Ajv blowup on deep nesting) ----

  const deepSpec = (depth: number, id: string): Record<string, unknown> => {
    let node: Record<string, unknown> = { type: "text", text: "leaf" };
    for (let i = 0; i < depth; i++) {
      node = { type: "column", children: [node] };
    }
    return minimalSpec({ children: [node] }, id);
  };

  test("depth 6 is accepted, depth 7 rejected with caps_exceeded + hint", async () => {
    await withHarness(async (h) => {
      const ok = await h.handle("/api/slates/deep-ok", {
        method: "PUT",
        headers: jsonHeaders(),
        body: JSON.stringify(deepSpec(5, "deep-ok")), // column chain 5 + text = depth 6
      });
      expect(ok.status).toBe(201);

      const bad = await h.handle("/api/slates/deep", {
        method: "PUT",
        headers: jsonHeaders(),
        body: JSON.stringify(deepSpec(6, "deep")), // depth 7
      });
      expect(bad.status).toBe(400);
      const err = (await bad.json()) as { error: { code: string; hint: string } };
      expect(err.error.code).toBe("caps_exceeded");
      expect(err.error.hint).toContain("depth");
    });
  });

  test("60 elements accepted, 61 rejected (containers count toward the total)", async () => {
    await withHarness(async (h) => {
      // 5 columns x 11 spacers = 55 + 5 columns = 60 total; per-container max 12 respected.
      const wide = (extra: number, id: string) =>
        minimalSpec(
          {
            children: Array.from({ length: 5 }, (_, c) => ({
              type: "column",
              children: Array.from({ length: 11 + (c === 0 ? extra : 0) }, () => ({ type: "spacer", height: 4 })),
            })),
          },
          id,
        );
      const ok = await h.handle("/api/slates/wide-ok", { method: "PUT", headers: jsonHeaders(), body: JSON.stringify(wide(0, "wide-ok")) });
      expect(ok.status).toBe(201);
      const bad = await h.handle("/api/slates/wide", { method: "PUT", headers: jsonHeaders(), body: JSON.stringify(wide(1, "wide")) });
      expect(bad.status).toBe(400);
      const err = (await bad.json()) as { error: { code: string; hint: string } };
      expect(err.error.code).toBe("caps_exceeded");
      expect(err.error.hint).toContain("60 elements");
    });
  });

  test("REGRESSION: depth-300 spec is rejected fast (must never wedge the event loop)", async () => {
    await withHarness(async (h) => {
      const started = Date.now();
      const r = await h.handle("/api/slates/deep300", {
        method: "PUT",
        headers: jsonHeaders(),
        body: JSON.stringify(deepSpec(300, "deep300")),
      });
      const elapsed = Date.now() - started;
      expect(r.status).toBe(400);
      expect(((await r.json()) as { error: { code: string } }).error.code).toBe("caps_exceeded");
      // Pre-fix, Ajv oneOf+allErrors blew up exponentially (>60s, wedging /health too).
      expect(elapsed).toBeLessThan(2000);
      // The server must still be responsive:
      const health = await h.handle("/health", { method: "GET" });
      expect(health.status).toBe(200);
    });
  });

  test("oversized Content-Length is rejected before the body is buffered (helper unit)", async () => {
    // NOTE: route integration is covered in scripts/e2e.sh — Bun treats Content-Length as a
    // forbidden header on constructed Requests, so it is only visible on real wire requests.
    const fakeReq = (cl: string | null) => ({ headers: new Headers(cl === null ? {} : { "content-length": cl }) }) as unknown as Request;
    expect(contentLengthTooLarge(fakeReq(null), 256 * 1024)).toBe(false);
    expect(contentLengthTooLarge(fakeReq("262144"), 256 * 1024)).toBe(false);
    expect(contentLengthTooLarge(fakeReq("262145"), 256 * 1024)).toBe(true);
    expect(contentLengthTooLarge(fakeReq("not-a-number"), 256 * 1024)).toBe(false);
  });
  test("oversized Content-Length is rejected before the body is buffered (helper unit)", async () => {
    // NOTE: route integration is covered in scripts/e2e.sh — Bun treats Content-Length as a
    // forbidden header on constructed Requests, so it is only visible on real wire requests.
    const fakeReq = (cl: string | null) => ({ headers: new Headers(cl === null ? {} : { "content-length": cl }) }) as unknown as Request;
    expect(contentLengthTooLarge(fakeReq(null), 256 * 1024)).toBe(false);
    expect(contentLengthTooLarge(fakeReq("262144"), 256 * 1024)).toBe(false);
    expect(contentLengthTooLarge(fakeReq("262145"), 256 * 1024)).toBe(true);
    expect(contentLengthTooLarge(fakeReq("not-a-number"), 256 * 1024)).toBe(false);
  });
});
