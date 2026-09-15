import { describe, expect, test } from "bun:test";
import { answer, expectErrorEnvelope, jsonHeaders, minimalSpec, postInteractions, putSlate, readGolden, withHarness } from "./helpers";

describe("content-hash ETag on device slates", () => {
  test("200 with quoted ETag, then 304 for If-None-Match", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "home", minimalSpec());
      const r1 = await h.get("/api/device/slates/home");
      expect(r1.status).toBe(200);
      const etag = r1.headers.get("etag")!;
      expect(etag).toMatch(/^"[0-9a-f]{64}"$/);
      const envelope = (await r1.json()) as Record<string, unknown>;
      expect(envelope["id"]).toBe("home");

      const r2 = await h.get("/api/device/slates/home", { "if-none-match": etag });
      expect(r2.status).toBe(304);
      expect(await r2.text()).toBe("");
      expect(r2.headers.get("etag")).toBe(etag);
    });
  });

  test("weak validators (W/) and unquoted hashes also match", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "home", minimalSpec());
      const etag = (await h.get("/api/device/slates/home")).headers.get("etag")!;
      const hash = etag.replaceAll('"', "");
      expect((await h.get("/api/device/slates/home", { "if-none-match": `W/${etag}` })).status).toBe(304);
      expect((await h.get("/api/device/slates/home", { "if-none-match": hash })).status).toBe(304);
      expect((await h.get("/api/device/slates/home", { "if-none-match": etag })).status).toBe(304);
    });
  });

  test("mismatched If-None-Match gets the full 200", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "home", minimalSpec());
      const r = await h.get("/api/device/slates/home", { "if-none-match": '"deadbeef"' });
      expect(r.status).toBe(200);
      expect(r.headers.get("etag")).toMatch(/^"[0-9a-f]{64}"$/);
    });
  });

  test("identical re-push keeps the same ETag; changed content moves it", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "home", minimalSpec());
      const etag1 = (await h.get("/api/device/slates/home")).headers.get("etag")!;
      await putSlate(h, "home", minimalSpec()); // identical re-push
      const etag2 = (await h.get("/api/device/slates/home")).headers.get("etag")!;
      expect(etag2).toBe(etag1); // free 304 for the device

      await putSlate(h, "home", minimalSpec({ title: "Changed" }));
      const etag3 = (await h.get("/api/device/slates/home")).headers.get("etag")!;
      expect(etag3).not.toBe(etag2);
      // old validator now misses
      expect((await h.get("/api/device/slates/home", { "if-none-match": etag2 })).status).toBe(200);
    });
  });

  test("check/uncheck interaction changes the ETag (server-owned state syncs to devices)", async () => {
    await withHarness(async (h) => {
      const spec = readGolden("dashboard.json");
      await putSlate(h, "dash", spec);
      const etag1 = (await h.get("/api/device/slates/dash")).headers.get("etag")!;
      await postInteractions(h, [{ seq: "c1", slateId: "dash", kind: "check", elementId: "focus", itemId: "call" }]);
      const etag2 = (await h.get("/api/device/slates/dash")).headers.get("etag")!;
      expect(etag2).not.toBe(etag1);
      expect((await h.get("/api/device/slates/dash", { "if-none-match": etag2 })).status).toBe(304);
    });
  });

  test("device list matches agent list (cheap hash-diff sync)", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "a", minimalSpec({ tone: "ok" }, "a"));
      await putSlate(h, "b", minimalSpec({ tone: "error" }, "b"));
      const agentList = (await (await h.get("/api/slates")).json()) as { slates: unknown[] };
      const deviceList = (await (await h.get("/api/device/slates")).json()) as { slates: unknown[] };
      expect(deviceList).toEqual(agentList);
    });
  });

  test("unknown slate 404 with envelope", async () => {
    await withHarness(async (h) => {
      const err = await expectErrorEnvelope(await h.get("/api/device/slates/ghost"), 404);
      expect(err.hint).toContain("ghost");
    });
  });
});
