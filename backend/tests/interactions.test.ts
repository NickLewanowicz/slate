import { describe, expect, test } from "bun:test";
import { answer, expectErrorEnvelope, jsonHeaders, minimalSpec, postInteractions, putSlate, readGolden, withHarness } from "./helpers";

const dashboard = () => readGolden("dashboard.json"); // todoList id "focus": items review/call/gym

describe("device interactions", () => {
  test("batch insert returns 202 with accepted/duplicate and records response-shaped rows", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", dashboard());
      const r = await postInteractions(h, [
        answer("s1", "dash", "edge-q", "o1", "Primary"),
        { seq: "s2", slateId: "dash", kind: "tap", elementId: "somewhere" },
      ]);
      expect(r.status).toBe(202);
      expect(await r.json()).toEqual({ accepted: 2, duplicate: 0 });

      const list = (await (await h.get("/api/slates/dash/responses")).json()) as {
        responses: Array<Record<string, unknown>>;
      };
      expect(list.responses.length).toBe(2);
      const first = list.responses[0];
      for (const field of ["id", "slateId", "kind", "elementId", "questionId", "optionId", "optionLabel", "itemId", "value", "clientAt", "stale", "createdAt"]) {
        expect(Object.keys(first)).toContain(field);
      }
      expect(first["slateId"]).toBe("dash");
      expect(first["optionLabel"]).toBe("Primary");
      expect(first["stale"]).toBe(false);
      // responses do NOT carry seq (internal idempotency key)
      expect(Object.keys(first)).not.toContain("seq");
    });
  });

  test("seq dedupe: replaying the same batch is fully duplicate, rows unchanged", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", dashboard());
      const batch = [answer("s1", "dash", "edge-q", "o1"), { seq: "s2", slateId: "dash", kind: "tap", elementId: "x" }];
      await postInteractions(h, batch);
      const r = await postInteractions(h, batch);
      expect(r.status).toBe(202);
      expect(await r.json()).toEqual({ accepted: 0, duplicate: 2 });
      const list = (await (await h.get("/api/slates/dash/responses")).json()) as { responses: unknown[] };
      expect(list.responses.length).toBe(2);
    });
  });

  test("mixed batch: new + duplicate counted separately", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", dashboard());
      await postInteractions(h, [answer("s1", "dash", "edge-q", "o1")]);
      const r = await postInteractions(h, [
        answer("s1", "dash", "edge-q", "o1"), // duplicate
        answer("s2", "dash", "edge-q", "o2", "Danger"), // new
      ]);
      expect(await r.json()).toEqual({ accepted: 1, duplicate: 1 });
    });
  });

  test("check/uncheck mutates the stored spec's todo item (server owns checked)", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", dashboard());
      const r = await postInteractions(h, [{ seq: "c1", slateId: "dash", kind: "check", elementId: "focus", itemId: "call" }]);
      expect(r.status).toBe(202);

      let envelope = (await (await h.get("/api/slates/dash")).json()) as { children: Array<Record<string, unknown>> };
      let todo = envelope.children.find((c) => c["id"] === "focus") as { items: Array<{ id: string; checked?: boolean }> };
      expect(todo.items.find((i) => i.id === "call")!.checked).toBe(true);
      expect(todo.items.find((i) => i.id === "review")!.checked).toBe(true); // untouched item keeps its state

      // contentHash changes so device hash-diff sync picks it up
      const list = (await (await h.get("/api/device/slates")).json()) as { slates: Array<{ contentHash: string }> };
      const hashAfterCheck = list.slates[0].contentHash;

      const r2 = await postInteractions(h, [{ seq: "c2", slateId: "dash", kind: "uncheck", elementId: "focus", itemId: "call" }]);
      expect(r2.status).toBe(202);
      envelope = (await (await h.get("/api/slates/dash")).json()) as { children: Array<Record<string, unknown>> };
      todo = envelope.children.find((c) => c["id"] === "focus") as { items: Array<{ id: string; checked?: boolean }> };
      expect(todo.items.find((i) => i.id === "call")!.checked ?? false).toBe(false);

      const list2 = (await (await h.get("/api/device/slates")).json()) as { slates: Array<{ contentHash: string }> };
      expect(list2.slates[0].contentHash).not.toBe(hashAfterCheck);
    });
  });

  test("unknown slate in batch -> 400, zero rows applied (all-or-nothing)", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", dashboard());
      const r = await postInteractions(h, [
        answer("s1", "dash", "edge-q", "o1"),
        answer("s2", "ghost", "q", "a"),
      ]);
      const err = await expectErrorEnvelope(r, 400);
      expect(err.code).toBe("unknown_slate");
      expect(err.hint).toContain("ghost");
      expect(err.hint).toContain("PUT /api/slates");
      const list = (await (await h.get("/api/slates/dash/responses")).json()) as { responses: unknown[] };
      expect(list.responses).toEqual([]);
    });
  });

  test("invalid kind is rejected by the interactions schema (400) with all-or-nothing", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", dashboard());
      const r = await postInteractions(h, [
        answer("s1", "dash", "edge-q", "o1"),
        { seq: "s2", slateId: "dash", kind: "swipe" } as unknown as { seq: string; slateId: string; kind: string },
      ]);
      const err = await expectErrorEnvelope(r, 400);
      expect(err.code).toBe("validation_error");
      expect(err.hint.toLowerCase()).toContain("answer");
      const list = (await (await h.get("/api/slates/dash/responses")).json()) as { responses: unknown[] };
      expect(list.responses).toEqual([]);
    });
  });

  test("schema-level batch violations: empty, >50, unknown field, missing seq, bad envelope", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", dashboard());

      let r = await postInteractions(h, []);
      let err = await expectErrorEnvelope(r, 400);
      expect(err.hint).toContain("1");

      const bigBatch = Array.from({ length: 51 }, (_, i) => ({ seq: `s${i}`, slateId: "dash", kind: "tap" }));
      r = await postInteractions(h, bigBatch);
      err = await expectErrorEnvelope(r, 400);
      expect(err.hint).toContain("50");

      r = await h.handle("/api/device/interactions", {
        method: "POST",
        headers: jsonHeaders(),
        body: JSON.stringify({ interactions: [{ seq: "s1", slateId: "dash", kind: "tap", optionLabelExtra: "nope" }] }),
      });
      err = await expectErrorEnvelope(r, 400);
      expect(err.hint).toContain("optionLabelExtra");

      r = await h.handle("/api/device/interactions", {
        method: "POST",
        headers: jsonHeaders(),
        body: JSON.stringify({ interactions: [{ slateId: "dash", kind: "tap" }] }),
      });
      err = await expectErrorEnvelope(r, 400);
      expect(err.message).toContain("seq");

      r = await h.handle("/api/device/interactions", { method: "POST", headers: jsonHeaders(), body: JSON.stringify({ nope: true }) });
      err = await expectErrorEnvelope(r, 400);
      expect(err.message).toContain("interactions");

      r = await h.handle("/api/device/interactions", { method: "POST", headers: jsonHeaders(), body: "{broken" });
      err = await expectErrorEnvelope(r, 400);
      expect(err.code).toBe("invalid_json");
    });
  });

  test("dangling check reference is recorded but does not crash or mutate", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", dashboard());
      const r = await postInteractions(h, [{ seq: "d1", slateId: "dash", kind: "check", elementId: "no-such-list", itemId: "no-item" }]);
      expect(r.status).toBe(202);
      const envelope = (await (await h.get("/api/slates/dash")).json()) as { children: Array<Record<string, unknown>> };
      const todo = envelope.children.find((c) => c["id"] === "focus") as { items: Array<{ id: string; checked?: boolean }> };
      expect(todo.items.find((i) => i.id === "call")!.checked ?? false).toBe(false);
      const list = (await (await h.get("/api/slates/dash/responses")).json()) as { responses: unknown[] };
      expect(list.responses.length).toBe(1);
    });
  });

  test("interactions are stored flattened AND as payload_json", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "dash", dashboard());
      await postInteractions(h, [{ seq: "s1", slateId: "dash", kind: "text", elementId: "edge-q", questionId: "edge-q", value: "do the thing" }]);
      const row = h.db.query<{ element_id: string | null; value: string | null; payload_json: string; kind: string }, []>(
        "SELECT element_id, value, payload_json, kind FROM interactions WHERE seq = 's1'",
      ).get()!;
      expect(row.kind).toBe("text");
      expect(row.element_id).toBe("edge-q");
      expect(row.value).toBe("do the thing");
      const payload = JSON.parse(row.payload_json) as Record<string, unknown>;
      expect(payload["slateId"]).toBe("dash");
      expect(payload["value"]).toBe("do the thing");
    });
  });

  test("minimal spec (no todoList) accepts answer interactions against question elements", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "job-alert", readGolden("question.json"));
      const r = await postInteractions(h, [answer("q1", "job-alert", "q-fail", "retry", "Retry")]);
      expect(r.status).toBe(202);
      expect(await r.json()).toEqual({ accepted: 1, duplicate: 0 });
    });
  });

  test("spec without expiresAt never marks stale; interactions to it still work", async () => {
    await withHarness(async (h) => {
      await putSlate(h, "home", minimalSpec());
      await postInteractions(h, [answer("s1", "home", "any-q", "a")]);
      const list = (await (await h.get("/api/slates/home/responses")).json()) as { responses: Array<{ stale: boolean }> };
      expect(list.responses[0].stale).toBe(false);
    });
  });

  test("stale flag: interactions arriving after the spec's expiresAt are marked stale", async () => {
    await withHarness(async (h) => {
      // surface already expired
      await putSlate(h, "old", minimalSpec({ expiresAt: "2020-01-01T00:00:00Z" }, "old"));
      await postInteractions(h, [
        answer("s1", "old", "q", "a"),
        { seq: "s2", slateId: "old", kind: "check", elementId: "l", itemId: "i" },
      ]);
      let list = (await (await h.get("/api/slates/old/responses")).json()) as { responses: Array<{ stale: boolean }> };
      expect(list.responses.map((x) => x.stale)).toEqual([true, true]);

      // live surface -> not stale
      await putSlate(h, "new", minimalSpec({ expiresAt: "2099-01-01T00:00:00Z" }, "new"));
      await postInteractions(h, [answer("s3", "new", "q", "a")]);
      list = (await (await h.get("/api/slates/new/responses")).json()) as { responses: Array<{ stale: boolean }> };
      expect(list.responses[0].stale).toBe(false);
    });
  });
});
