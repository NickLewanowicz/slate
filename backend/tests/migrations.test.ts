import { describe, expect, test } from "bun:test";
import { Database } from "bun:sqlite";
import { mkdirSync, rmSync } from "node:fs";
import { join } from "node:path";
import { loadConfig, ConfigError } from "../src/config";
import { DEFAULT_MIGRATIONS_DIR, ensureDbDir, migrate, migrationNames, openDb } from "../src/db";
import { canonicalJson, sha256Hex } from "../src/canonical";
import { withHarness, BASE } from "./helpers";

describe("migrations", () => {
  test("fresh in-memory boot applies 0001 and tracks it in _migrations", () => {
    const db = new Database(":memory:");
    const result = migrate(db, DEFAULT_MIGRATIONS_DIR);
    expect(result.applied).toEqual(["0001_init.sql"]);
    expect(migrationNames(db)).toEqual(["0001_init.sql"]);
    const tables = db
      .query<{ name: string }, []>("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
      .all()
      .map((r) => r.name);
    expect(tables).toContain("slates");
    expect(tables).toContain("interactions");
    expect(tables).toContain("_migrations");
    db.close();
  });

  test("migrations are idempotent (second run applies nothing)", () => {
    const db = new Database(":memory:");
    migrate(db, DEFAULT_MIGRATIONS_DIR);
    const second = migrate(db, DEFAULT_MIGRATIONS_DIR);
    expect(second.applied).toEqual([]);
    expect(second.skipped).toEqual(["0001_init.sql"]);
    expect(migrationNames(db)).toEqual(["0001_init.sql"]);
    db.close();
  });

  test("file database gets WAL, foreign_keys and busy_timeout pragmas", () => {
    const tmp = join(import.meta.dir, "../.tmp-test-migrations");
    const dbPath = join(tmp, "test.db");
    rmSync(tmp, { recursive: true, force: true });
    ensureDbDir(dbPath);
    const db = openDb(dbPath);
    try {
      const journalMode = db.query<{ journal_mode: string }, []>("PRAGMA journal_mode").get()!.journal_mode;
      expect(journalMode).toBe("wal");
      const fk = db.query<{ foreign_keys: number }, []>("PRAGMA foreign_keys").get()!.foreign_keys;
      expect(fk).toBe(1);
      const busy = db.query<{ timeout: number }, []>("PRAGMA busy_timeout").get()!.timeout;
      expect(busy).toBe(5000);
      migrate(db, DEFAULT_MIGRATIONS_DIR);
      expect(migrationNames(db)).toEqual(["0001_init.sql"]);
    } finally {
      db.close();
      rmSync(tmp, { recursive: true, force: true });
    }
  });

  test("interactions cascade on slate delete (FK)", () => {
    const db = new Database(":memory:");
    migrate(db, DEFAULT_MIGRATIONS_DIR);
    db.run("INSERT INTO slates (slate_id, spec_json, content_hash, tone, updated_at) VALUES ('s1', '{}', 'h', 'neutral', 'now')");
    db.run(
      "INSERT INTO interactions (slate_id, kind, seq, payload_json, created_at) VALUES ('s1', 'tap', 'x1', '{}', 'now')",
    );
    expect(db.query<{ n: number }, []>("SELECT COUNT(*) AS n FROM interactions").get()!.n).toBe(1);
    db.run("PRAGMA foreign_keys = ON");
    db.run("DELETE FROM slates WHERE slate_id = 's1'");
    expect(db.query<{ n: number }, []>("SELECT COUNT(*) AS n FROM interactions").get()!.n).toBe(0);
    db.close();
  });
});

describe("config", () => {
  test("boot fails without SLATE_API_KEY", () => {
    expect(() => loadConfig({})).toThrow(ConfigError);
    expect(() => loadConfig({ SLATE_API_KEY: "  " })).toThrow(/SLATE_API_KEY/);
    try {
      loadConfig({});
    } catch (error) {
      expect((error as Error).message).toContain("Authorization: Bearer");
    }
  });

  test("defaults", () => {
    const config = loadConfig({ SLATE_API_KEY: "k" });
    expect(config.port).toBe(3000);
    expect(config.dbPath).toBe("./data/slate.db");
    expect(config.webhookUrl).toBeUndefined();
    expect(config.webhookSecret).toBeUndefined();
    expect(config.maxResponsesPerSlate).toBe(200);
    expect(config.responseTtlHours).toBe(168);
    expect(config.logLevel).toBe("info");
    expect(config.sweepDisabled).toBe(false);
    expect(config.webhookRetryDelaysMs).toEqual([2000, 8000, 30000]);
    expect(config.version.length).toBeGreaterThan(0);
  });

  test("parses valid env values", () => {
    const config = loadConfig({
      SLATE_API_KEY: "k",
      SLATE_PORT: "8080",
      SLATE_DB_PATH: "/tmp/x.db",
      SLATE_WEBHOOK_URL: "http://gw:1234/wake",
      SLATE_WEBHOOK_SECRET: "s3cret",
      SLATE_MAX_RESPONSES_PER_SLATE: "50",
      SLATE_RESPONSE_TTL_HOURS: "24",
      SLATE_LOG_LEVEL: "debug",
      SLATE_SWEEP_DISABLED: "1",
    });
    expect(config.port).toBe(8080);
    expect(config.dbPath).toBe("/tmp/x.db");
    expect(config.webhookUrl).toBe("http://gw:1234/wake");
    expect(config.webhookSecret).toBe("s3cret");
    expect(config.maxResponsesPerSlate).toBe(50);
    expect(config.responseTtlHours).toBe(24);
    expect(config.logLevel).toBe("debug");
    expect(config.sweepDisabled).toBe(true);
  });

  test("fail-fast on bad env values", () => {
    expect(() => loadConfig({ SLATE_API_KEY: "k", SLATE_PORT: "nope" })).toThrow(/SLATE_PORT/);
    expect(() => loadConfig({ SLATE_API_KEY: "k", SLATE_PORT: "99999" })).toThrow(/SLATE_PORT/);
    expect(() => loadConfig({ SLATE_API_KEY: "k", SLATE_LOG_LEVEL: "loud" })).toThrow(/SLATE_LOG_LEVEL/);
    expect(() => loadConfig({ SLATE_API_KEY: "k", SLATE_MAX_RESPONSES_PER_SLATE: "-1" })).toThrow(/SLATE_MAX_RESPONSES_PER_SLATE/);
    expect(() => loadConfig({ SLATE_API_KEY: "k", SLATE_RESPONSE_TTL_HOURS: "x" })).toThrow(/SLATE_RESPONSE_TTL_HOURS/);
    expect(() => loadConfig({ SLATE_API_KEY: "k", SLATE_WEBHOOK_URL: "not-a-url" })).toThrow(/SLATE_WEBHOOK_URL/);
    expect(() => loadConfig({ SLATE_API_KEY: "k", SLATE_WEBHOOK_URL: "ftp://gw" })).toThrow(/SLATE_WEBHOOK_URL/);
  });

  test("overrides win over env (used by tests)", () => {
    const config = loadConfig({ SLATE_API_KEY: "k", SLATE_PORT: "1234" }, { sweepDisabled: true, webhookRetryDelaysMs: [1] });
    expect(config.port).toBe(1234);
    expect(config.sweepDisabled).toBe(true);
    expect(config.webhookRetryDelaysMs).toEqual([1]);
  });
});

describe("canonical json + hashing", () => {
  test("sorts keys recursively and minifies", () => {
    const value = { b: 1, a: { y: 2, x: [3, { d: 4, c: 5 }] } };
    expect(canonicalJson(value)).toBe('{"a":{"x":[3,{"c":5,"d":4}],"y":2},"b":1}');
  });

  test("canonical form is order-independent", () => {
    const a = { id: "home", children: [{ type: "text", text: "hi", id: "t1" }] };
    const b = { children: [{ id: "t1", text: "hi", type: "text" }], id: "home" };
    expect(canonicalJson(a)).toBe(canonicalJson(b));
    expect(sha256Hex(canonicalJson(a))).toBe(sha256Hex(canonicalJson(b)));
  });

  test("sha256Hex is stable hex sha256", () => {
    expect(sha256Hex("abc")).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  });
});

describe("unauthenticated surface", () => {
  test("GET /health is open and returns {status:'ok'}", async () => {
    await withHarness(async (h) => {
      const r = await h.raw("/health");
      expect(r.status).toBe(200);
      expect(await r.json()).toEqual({ status: "ok" });
    });
  });

  test("GET / is open and identifies the service", async () => {
    await withHarness(async (h) => {
      const r = await h.raw("/");
      expect(r.status).toBe(200);
      const body = (await r.json()) as { service: string; version: string };
      expect(body.service).toBe("slate-backend");
      expect(body.version.length).toBeGreaterThan(0);
    });
  });

  test("health endpoint never requires auth even with a wrong key present", async () => {
    await withHarness(async (h) => {
      const r = await h.app.handle(new Request(`${BASE}/health`, { headers: { authorization: "Bearer wrong" } }));
      expect(r.status).toBe(200);
    });
  });
});
