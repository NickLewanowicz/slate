import { Database } from "bun:sqlite";
import { mkdirSync, readdirSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

export const DEFAULT_MIGRATIONS_DIR = resolve(import.meta.dir, "../migrations");

const MIGRATION_NAME_RE = /^(\d{4})_[a-zA-Z0-9_]+\.sql$/;

export function openDb(path: string): Database {
  const db = new Database(path);
  // WAL for concurrent readers; FK cascade for slate deletion; sane lock wait.
  db.exec("PRAGMA journal_mode = WAL;");
  db.exec("PRAGMA foreign_keys = ON;");
  db.exec("PRAGMA busy_timeout = 5000;");
  return db;
}

export function ensureDbDir(dbPath: string): void {
  const dir = dirname(dbPath);
  if (dir && dir !== "." && dir !== ":memory:") {
    mkdirSync(dir, { recursive: true });
  }
}

export interface MigrationResult {
  applied: string[];
  skipped: string[];
}

/**
 * Applies numbered migrations from `dir` (default backend/migrations) in
 * filename order, each in its own transaction, tracked in `_migrations`.
 * Safe to run repeatedly (idempotent).
 */
export function migrate(db: Database, dir: string = DEFAULT_MIGRATIONS_DIR): MigrationResult {
  db.exec(`CREATE TABLE IF NOT EXISTS _migrations (
    name TEXT PRIMARY KEY,
    applied_at TEXT NOT NULL
  );`);

  const files = readdirSync(dir)
    .filter((f) => MIGRATION_NAME_RE.test(f))
    .sort();

  const alreadyApplied = new Set<string>(
    db.query<{ name: string }, []>("SELECT name FROM _migrations").all().map((r) => r.name),
  );

  const applied: string[] = [];
  const skipped: string[] = [];
  const record = db.prepare("INSERT INTO _migrations (name, applied_at) VALUES (?, ?)");
  const stamp = () => new Date().toISOString();

  for (const file of files) {
    if (alreadyApplied.has(file)) {
      skipped.push(file);
      continue;
    }
    const sql = readFileSync(resolve(dir, file), "utf8");
    const run = db.transaction(() => {
      db.exec(sql);
      record.run(file, stamp());
    });
    run();
    applied.push(file);
  }
  return { applied, skipped };
}

export function migrationNames(db: Database): string[] {
  return db.query<{ name: string }, []>("SELECT name FROM _migrations ORDER BY name").all().map((r) => r.name);
}
