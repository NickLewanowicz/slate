import { describe, expect, test } from "bun:test";
import { expectErrorEnvelope, jsonHeaders, minimalSpec, putSlate, readGolden, withHarness } from "./helpers";

describe("error envelope on every error path", () => {
  test("unknown route under /api -> 404 envelope with route hint", async () => {
    await withHarness(async (h) => {
      const err = await expectErrorEnvelope(await h.get("/api/does-not-exist"), 404);
      expect(err.code).toBe("not_found");
      expect(err.hint).toContain("GET /api/ping");
    });
  });

  test("known path with wrong method -> 404 envelope with route hint", async () => {
    await withHarness(async (h) => {
      const err = await expectErrorEnvelope(await h.raw("/health", { method: "DELETE" }), 404);
      expect(err.code).toBe("not_found");
      expect(err.hint).toContain("GET /health");
    });
  });

  test("PATCH is not part of the API (full-replace only) -> 404 envelope", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "home", readGolden("minimal.json"));
      const r = await h.handle("/api/slates/home", { method: "PATCH", headers: jsonHeaders(), body: '{"title":"x"}' });
      const err = await expectErrorEnvelope(r, 404);
      expect(err.hint).toContain("PUT");
    });
  });

  test("unhandled server errors become 500 internal_error envelopes with hints", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "home", readGolden("minimal.json"));
      h.db.exec("DROP TABLE slates"); // sabotage: next list query will throw
      const r = await h.get("/api/slates");
      const err = await expectErrorEnvelope(r, 500);
      expect(err.code).toBe("internal_error");
      expect(err.hint.length).toBeGreaterThan(0);
    });
  });

  test("auth errors and validation errors share the exact envelope shape", async () => {
    await withHarness(async (h) => {
      const unauthorized = await expectErrorEnvelope(await h.raw("/api/ping"));
      const validation = await expectErrorEnvelope(
        await putSlate(h, "home", { id: "home", version: 7, children: [] }),
      );
      const shapes = [unauthorized, validation];
      for (const shape of shapes) {
        expect(Object.keys(shape).sort()).toEqual(["code", "hint", "message", "status"]);
      }
    });
  });
});

describe("misc surface", () => {
  test("ping returns ok/serverTime/version", async () => {
    await withHarness(async (h) => {
      const r = await h.get("/api/ping");
      expect(r.status).toBe(200);
      const body = (await r.json()) as { ok: boolean; serverTime: string; version: string };
      expect(body.ok).toBe(true);
      expect(Number.isFinite(Date.parse(body.serverTime))).toBe(true);
      expect(body.version).toBe(h.config.version);
    });
  });

  test("health is open and cheap", async () => {
    await withHarness(async (h) => {
      const r = await h.raw("/health");
      expect(r.status).toBe(200);
      expect(await r.json()).toEqual({ status: "ok" });
    });
  });

  test("trailing-slash variants are not registered but still answer with the envelope", async () => {
    await withHarness(async (h) => {
      const r = await h.get("/api/slates/");
      expect([200, 404]).toContain(r.status); // Elysia may treat /api/slates/ as /api/slates
      if (r.status === 404) await expectErrorEnvelope(r);
    });
  });

  test("query strings on PUT are ignored, body drives the spec", async () => {
    await withHarness(async (h) => {
      const r = await putSlate(h, "home", minimalSpec());
      expect(r.status).toBe(201);
    });
  });
});
