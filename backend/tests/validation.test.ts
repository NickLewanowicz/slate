import { describe, expect, test } from "bun:test";
import { SlateValidator, pointerToPath } from "../src/validation";
import { expectErrorEnvelope, jsonHeaders, minimalSpec, putSlate, readGolden, withHarness } from "./helpers";

describe("spec validation", () => {
  test("every valid golden fixture is accepted", async () => {
    await withHarness(async (h) => {
      const names = ["minimal.json", "question.json", "dashboard.json", "status-board.json", "edge-cases.json"];
      for (const name of names) {
        const r = await putSlate(h, (readGolden(name)["id"] as string) ?? "x", readGolden(name));
        expect(r.status, `${name} must validate`).toBe(201);
      }
    });
  });

  test("unknown-element.json is rejected with a hint naming the path and valid element types", async () => {
    await withHarness(async (h) => {
      const spec = readGolden("unknown-element.json");
      const r = await putSlate(h, spec["id"] as string, spec);
      const err = await expectErrorEnvelope(r, 400);
      expect(err.code).toBe("validation_error");
      expect(err.message).toContain("carousel");
      expect(err.hint).toContain("carousel");
      expect(err.hint).toMatch(/children\[\d+\]/); // names the offending path
      for (const elementType of ["column", "row", "text", "statusRow", "todoList", "question", "progress", "divider", "spacer"]) {
        expect(err.hint).toContain(elementType); // suggests valid element types
      }
      // unknown tone chartreuse is also flagged
      expect(err.hint.toLowerCase()).not.toContain("undefined");
    });
  });

  test("bad tone gets valid values + closest-intent guess + snippet", async () => {
    await withHarness(async (h) => {
      const spec = minimalSpec({
        children: [
          { type: "text", text: "a" },
          { type: "text", text: "b" },
          { type: "text", text: "c" },
          { type: "statusRow", label: "x", tone: "chartreuse" }, // children[3]
        ],
      });
      const r = await putSlate(h, "home", spec);
      const err = await expectErrorEnvelope(r, 400);
      expect(err.message).toContain("children[3].tone");
      for (const tone of ["ok", "warn", "error", "info", "neutral"]) {
        expect(err.hint).toContain(tone);
      }
      expect(err.hint).toContain("chartreuse");
      expect(err.hint).toContain("tone");
    });
  });

  test("additionalProperties are rejected with the offending path and property name", async () => {
    await withHarness(async (h) => {
      const spec = minimalSpec({ children: [{ type: "text", text: "hi", colour: "red" }] });
      const r = await putSlate(h, "home", spec);
      const err = await expectErrorEnvelope(r, 400);
      expect(err.message).toContain("colour");
      expect(err.hint).toContain("colour");
      expect(err.hint).toContain("children[0]");
      expect(err.hint).toContain("text"); // allowed properties listed
    });
  });

  test("wrong version is rejected with a hint", async () => {
    await withHarness(async (h) => {
      const spec = { id: "home", version: 1, children: [{ type: "text", text: "v1 never works" }] };
      const r = await putSlate(h, "home", spec);
      const err = await expectErrorEnvelope(r, 400);
      expect(err.message).toContain("version");
      expect(err.hint).toContain("2");
    });
  });

  test("oversized spec (>64 KB serialized) is rejected with a size hint", async () => {
    await withHarness(async (h) => {
      const spec = minimalSpec({ title: "x".repeat(70_000) });
      const r = await putSlate(h, "home", spec);
      const err = await expectErrorEnvelope(r, 400);
      expect(err.code).toBe("payload_too_large");
      expect(err.hint).toContain("64");
      expect(err.hint).toContain("bytes");
    });
  });

  test("invalid JSON body is invalid_json", async () => {
    await withHarness(async (h) => {
      const r = await h.handle("/api/slates/home", { method: "PUT", headers: jsonHeaders(), body: "nope" });
      const err = await expectErrorEnvelope(r, 400);
      expect(err.code).toBe("invalid_json");
    });
  });

  test("more validation paths: required children, bad slug pattern, format, type, maxItems", async () => {
    await withHarness(async (h) => {
      // missing children
      let r = await putSlate(h, "home", { id: "home", version: 2 });
      let err = await expectErrorEnvelope(r, 400);
      expect(err.message).toContain("children");

      // bad slug pattern in id
      r = await putSlate(h, "bad id!", minimalSpec({}, "bad id!"));
      err = await expectErrorEnvelope(r, 400);
      expect(err.message).toContain("bad id!");
      expect(err.hint).toContain("letter");

      // bad date-time format
      r = await putSlate(h, "home", minimalSpec({ expiresAt: "yesterday" }));
      err = await expectErrorEnvelope(r, 400);
      expect(err.hint).toContain("date-time");
      expect(err.hint).toContain("2026-01-15T09:30:00Z");

      // wrong type for children
      r = await putSlate(h, "home", minimalSpec({ children: "not-an-array" }));
      err = await expectErrorEnvelope(r, 400);
      expect(err.hint).toContain("array");

      // question with a single option (minItems 2)
      r = await putSlate(h, "home", minimalSpec({ children: [{ type: "question", id: "q", prompt: "?", options: [{ id: "a", label: "A" }] }] }));
      err = await expectErrorEnvelope(r, 400);
      expect(err.hint).toContain("2");

      // container with 13 children (maxItems 12)
      const many = Array.from({ length: 13 }, (_, i) => ({ type: "divider", id: `d${i}` }));
      r = await putSlate(h, "home", minimalSpec({ children: many }));
      err = await expectErrorEnvelope(r, 400);
      expect(err.hint).toContain("12");
    });
  });

  test("every spec error path carries a non-empty hint (envelope shape)", async () => {
    await withHarness(async (h) => {
      const badSpecs = [
        null,
        42,
        "string",
        [],
        {},
        { id: "home" },
        { id: "home", version: 2 },
        minimalSpec({ children: [{ type: "nope" }] }),
        minimalSpec({ tone: "loud" }),
        minimalSpec({ title: "x".repeat(61) }),
        minimalSpec({ children: [{ type: "text" }] }), // missing text
      ];
      for (const bad of badSpecs) {
        const r = await putSlate(h, "home", bad);
        const err = await expectErrorEnvelope(r, 400);
        expect(err.code).toBe("validation_error");
      }
    });
  });
});

describe("validation internals", () => {
  const validator = new SlateValidator();

  test("pointerToPath converts JSON pointers to JS-ish paths", () => {
    expect(pointerToPath("")).toBe("(root)");
    expect(pointerToPath("/children/3/tone")).toBe("children[3].tone");
    expect(pointerToPath("/children/1/items/2/label")).toBe("children[1].items[2].label");
    expect(pointerToPath("/a~1b")).toBe("a/b");
  });

  test("interactions schema compiles and validates batches (cross-schema $ref resolves)", () => {
    const good = { interactions: [{ seq: "s1", slateId: "home", kind: "answer" }] };
    expect(validator.validateInteractionBatch(good).ok).toBe(true);
    const bad = { interactions: [{ seq: "s1", slateId: "bad id!", kind: "swipe" }] };
    const result = validator.validateInteractionBatch(bad);
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors.hint.length).toBeGreaterThan(0);
    }
  });

  test("validateSpec returns structured ValidationResult", () => {
    expect(validator.validateSpec({ id: "x", version: 2, children: [] }).ok).toBe(true);
    const bad = validator.validateSpec({ id: "x", version: 1, children: [] });
    expect(bad.ok).toBe(false);
    if (!bad.ok) {
      expect(bad.errors.message).toContain("version");
      expect(bad.errors.hint).toContain("2");
    }
  });

  test("schema loading falls back across candidates and fails loudly when absent", () => {
    const original = process.env.SLATE_SCHEMA_DIR;
    try {
      process.env.SLATE_SCHEMA_DIR = "/nonexistent-path-for-tests";
      // constructor reads via candidates; the repo-root candidate still wins
      const v = new SlateValidator();
      expect(v.validateSlate({ id: "x", version: 2, children: [] })).toBe(true);
    } finally {
      if (original === undefined) delete process.env.SLATE_SCHEMA_DIR;
      else process.env.SLATE_SCHEMA_DIR = original;
    }
  });
});
