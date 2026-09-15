import { describe, expect, test } from "bun:test";
import { answer, expectErrorEnvelope, minimalSpec, postInteractions, putSlate, readGolden, withHarness } from "./helpers";

async function seed(h: import("./helpers").TestHarness, n: number, slateId = "dash") {
  for (let i = 1; i <= n; i += 50) {
    const chunk = [];
    for (let j = i; j < Math.min(i + 50, n + 1); j++) {
      chunk.push(answer(`s${j}`, slateId, "edge-q", "o1"));
    }
    const r = await postInteractions(h, chunk);
    expect(r.status).toBe(202);
  }
}

describe("responses listing + ack", () => {
  test("ascending order, cursor = last id, hasMore=false on a single page", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", readGolden("dashboard.json"));
      await seed(h, 5);
      const r = await h.get("/api/slates/dash/responses");
      expect(r.status).toBe(200);
      const body = (await r.json()) as { responses: Array<{ id: number }>; cursor: number; hasMore: boolean };
      expect(body.responses.map((x) => x.id)).toEqual([1, 2, 3, 4, 5]);
      expect(body.cursor).toBe(5);
      expect(body.hasMore).toBe(false);
    });
  });

  test("cursor pagination: limit, hasMore, since walk", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", readGolden("dashboard.json"));
      await seed(h, 5);
      const page1 = (await (await h.get("/api/slates/dash/responses?limit=2")).json()) as {
        responses: Array<{ id: number }>;
        cursor: number;
        hasMore: boolean;
      };
      expect(page1.responses.map((x) => x.id)).toEqual([1, 2]);
      expect(page1.cursor).toBe(2);
      expect(page1.hasMore).toBe(true);

      const page2 = (await (await h.get(`/api/slates/dash/responses?limit=2&since=${page1.cursor}`)).json()) as typeof page1;
      expect(page2.responses.map((x) => x.id)).toEqual([3, 4]);
      expect(page2.hasMore).toBe(true);

      const page3 = (await (await h.get(`/api/slates/dash/responses?limit=2&since=${page2.cursor}`)).json()) as typeof page1;
      expect(page3.responses.map((x) => x.id)).toEqual([5]);
      expect(page3.cursor).toBe(5);
      expect(page3.hasMore).toBe(false);

      // since beyond the last id -> empty page, hasMore false
      const empty = (await (await h.get("/api/slates/dash/responses?since=999")).json()) as typeof page1;
      expect(empty.responses).toEqual([]);
      expect(empty.hasMore).toBe(false);
      expect(empty.cursor).toBe(999);
    });
  });

  test("default limit 50; limit boundary 200 accepted", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", readGolden("dashboard.json"));
      await seed(h, 60);
      const page1 = (await (await h.get("/api/slates/dash/responses")).json()) as { responses: unknown[]; hasMore: boolean };
      expect(page1.responses.length).toBe(50);
      expect(page1.hasMore).toBe(true);

      const r200 = await h.get("/api/slates/dash/responses?limit=200");
      expect(r200.status).toBe(200);
      const body200 = (await r200.json()) as { responses: unknown[]; hasMore: boolean };
      expect(body200.responses.length).toBe(60);
      expect(body200.hasMore).toBe(false);
    });
  });

  test("empty page returns cursor=0 (never null) so agents can echo it back as since", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", readGolden("dashboard.json"));
      const r = await h.get("/api/slates/dash/responses?since=0");
      expect(r.status).toBe(200);
      const body = (await r.json()) as { responses: unknown[]; cursor: number; hasMore: boolean };
      expect(body.responses).toEqual([]);
      expect(body.cursor).toBe(0);
      expect(body.hasMore).toBe(false);
    });
  });

  test("since=0 returns all responses from the start", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", readGolden("dashboard.json"));
      await postInteractions(h, [
        { seq: "s0", slateId: "dash", kind: "tap", elementId: "e1" },
        { seq: "s1", slateId: "dash", kind: "tap", elementId: "e2" },
      ]);
      const r = await h.get("/api/slates/dash/responses?since=0");
      expect(r.status).toBe(200);
      const body = (await r.json()) as { responses: unknown[]; cursor: number };
      expect(body.responses.length).toBe(2);
      expect(body.cursor).toBe(2);
    });
  });

  test("invalid limit/since values -> 400 with hint", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", readGolden("dashboard.json"));
      for (const query of ["limit=201", "limit=0", "limit=-3", "limit=abc", "since=xyz", "since=-1"]) {
        const r = await h.get(`/api/slates/dash/responses?${query}`);
        const err = await expectErrorEnvelope(r, 400);
        expect(err.code, `query "${query}" should 400`).toBe("invalid_query");
      }
    });
  });

  test("responses of an unknown slate 404", async () => {
    await withHarness(async (h) => {
      const err = await expectErrorEnvelope(await h.get("/api/slates/ghost/responses"), 404);
      expect(err.hint).toContain("PUT /api/slates/ghost");
    });
  });

  test("DELETE ack with beforeId is INCLUSIVE of the cursor (agent acks last-seen id, nothing re-delivered)", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", readGolden("dashboard.json"));
      await seed(h, 5);
      const r = await h.handle("/api/slates/dash/responses?beforeId=3", { method: "DELETE" });
      expect(r.status).toBe(200);
      expect(await r.json()).toEqual({ deleted: 3 });
      const body = (await (await h.get("/api/slates/dash/responses")).json()) as { responses: Array<{ id: number }> };
      expect(body.responses.map((x) => x.id)).toEqual([4, 5]);

      // beforeId beyond the last id deletes everything
      const r2 = await h.handle("/api/slates/dash/responses?beforeId=999", { method: "DELETE" });
      expect(await r2.json()).toEqual({ deleted: 2 });
      const body2 = (await (await h.get("/api/slates/dash/responses")).json()) as { responses: unknown[] };
      expect(body2.responses).toEqual([]);
    });
  });

  test("DELETE without beforeId deletes all for the slate", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", readGolden("dashboard.json"));
      await seed(h, 4);
      const r = await h.handle("/api/slates/dash/responses", { method: "DELETE" });
      expect(r.status).toBe(200);
      expect(await r.json()).toEqual({ deleted: 4 });
      const body = (await (await h.get("/api/slates/dash/responses")).json()) as { responses: unknown[] };
      expect(body.responses).toEqual([]);
    });
  });

  test("DELETE with invalid beforeId -> 400; unknown slate -> 404", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", readGolden("dashboard.json"));
      const err = await expectErrorEnvelope(
        await h.handle("/api/slates/dash/responses?beforeId=nope", { method: "DELETE" }),
        400,
      );
      expect(err.hint).toContain("beforeId");
      await expectErrorEnvelope(await h.handle("/api/slates/ghost/responses", { method: "DELETE" }), 404);
    });
  });
});

describe("retention", () => {
  test("per-slate cap keeps only the newest N interactions (enforced on insert)", async () => {
    await withHarness(
      async (h) => {
        expect(h.config.maxResponsesPerSlate).toBe(3);
        await putSlate(h, "dash", readGolden("dashboard.json"));
        await seed(h, 5);
        const ids = h.db.query<{ id: number }, []>("SELECT id FROM interactions ORDER BY id").all().map((r) => r.id);
        expect(ids).toEqual([3, 4, 5]);
        // a small slate with few rows is untouched
        await putSlate(h, "tiny", minimalSpec({}, "tiny"));
        await postInteractions(h, [answer("t1", "tiny", "q", "a")]);
        const tinyIds = h.db
          .query<{ n: number }, [string]>("SELECT COUNT(*) AS n FROM interactions WHERE slate_id = ?")
          .get("tiny")!.n;
        expect(tinyIds).toBe(1);
      },
      { config: { maxResponsesPerSlate: 3 } },
    );
  });

  test("TTL drops interactions older than responseTtlHours (enforced on insert)", async () => {
    await withHarness(
      async (h) => {
        expect(h.config.responseTtlHours).toBe(1);
        await putSlate(h, "dash", readGolden("dashboard.json"));
        await seed(h, 3);
        // Age the first two interactions beyond the TTL, then trigger insert-time enforcement.
        const old = new Date(Date.now() - 5 * 3_600_000).toISOString();
        h.db.run("UPDATE interactions SET created_at = ? WHERE id IN (1, 2)", [old]);
        await postInteractions(h, [answer("s-new", "dash", "edge-q", "o2", "Danger")]);
        const ids = h.db.query<{ id: number }, []>("SELECT id FROM interactions ORDER BY id").all().map((r) => r.id);
        expect(ids).toEqual([3, 4]);
      },
      { config: { responseTtlHours: 1 } },
    );
  });

  test("sweepAll enforces TTL + per-slate caps across all slates", async () => {
    await withHarness(async (h) => {
      const { loadConfig } = await import("../src/config");
      const { sweepAll } = await import("../src/retention");
      const config = loadConfig({ SLATE_API_KEY: "test-key" }, { sweepDisabled: true, responseTtlHours: 1 });
      await putSlate(h, "dash", readGolden("dashboard.json"));
      await seed(h, 3);
      h.db.run("UPDATE interactions SET created_at = ?", [new Date(Date.now() - 5 * 3_600_000).toISOString()]);
      const removed = sweepAll(h.db, config);
      expect(removed).toBe(3);
      const ids = h.db.query<{ id: number }, []>("SELECT id FROM interactions").all().map((r) => r.id);
      expect(ids).toEqual([]);
    });
  });

  test("createSweeper runs ticks and stop() clears them", async () => {
    await withHarness(async (h) => {
      const { loadConfig } = await import("../src/config");
      const { createSweeper } = await import("../src/retention");
      const config = loadConfig({ SLATE_API_KEY: "test-key" }, { sweepDisabled: false, responseTtlHours: 0 });
      await putSlate(h, "dash", readGolden("dashboard.json"));
      await seed(h, 2);
      const sweeper = createSweeper(h.db, config, () => undefined, 5);
      await new Promise((resolvePromise) => setTimeout(resolvePromise, 30));
      const remaining = h.db.query<{ n: number }, []>("SELECT COUNT(*) AS n FROM interactions").get()!.n;
      expect(remaining).toBe(0); // ttl 0 sweeps everything
      sweeper.stop();
    });
  });

  test("sweeper respects SLATE_SWEEP_DISABLED (no-op sweeper)", async () => {
    await withHarness(async (h) => {
      expect(h.config.sweepDisabled).toBe(true);
      expect(h.app.sweeper.stop()).toBeUndefined(); // no-op, does not throw
    });
  });
});
