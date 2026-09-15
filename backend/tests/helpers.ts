import { type Database } from "bun:sqlite";
import { expect } from "bun:test";
import { readFileSync } from "node:fs";
import { buildApp, type SlateApp } from "../src/app";
import { loadConfig, type AppConfig } from "../src/config";
import { DEFAULT_MIGRATIONS_DIR, migrate, openDb } from "../src/db";

export const API_KEY = "test-key";
export const BASE = "http://localhost:3000";
export const GOLDEN_DIR = new URL("../../schema/golden/", import.meta.url).pathname;

export function readGolden(name: string): Record<string, unknown> {
  return JSON.parse(readFileSync(`${GOLDEN_DIR}${name}`, "utf8"));
}

export interface StubCall {
  url: string;
  method: string;
  headers: Headers;
  body: string;
}

export interface WebhookStub {
  calls: StubCall[];
  /** Behavior for subsequent calls: "ok" (default) | number (HTTP status) | "throw". */
  behavior: "ok" | number | "throw";
  /** If set, statuses are consumed one per call in order, then behavior takes over. */
  statusQueue: number[];
}

export interface TestHarness {
  app: SlateApp;
  db: Database;
  config: AppConfig;
  /** GET with auth. */
  get: (path: string, extraHeaders?: Record<string, string>) => Promise<Response>;
  /** Request with auth and arbitrary init. */
  handle: (path: string, init?: RequestInit) => Promise<Response>;
  /** Request with NO auth header (for 401 tests). */
  raw: (path: string, init?: RequestInit) => Promise<Response>;
  webhookStub: WebhookStub;
}

const realFetch = globalThis.fetch;

export function withHarness<T>(fn: (h: TestHarness) => T | Promise<T>, opts: { config?: Partial<AppConfig> } = {}): Promise<T> {
  return (async () => {
    // openDb (not raw Database) so tests run with the same pragmas as production (WAL, FK cascade, busy_timeout).
    const db = openDb(":memory:");
    migrate(db, DEFAULT_MIGRATIONS_DIR);
    const config = loadConfig(
      { SLATE_API_KEY: API_KEY },
      {
        sweepDisabled: true,
        webhookRetryDelaysMs: [1, 1, 1],
        ...opts.config,
      },
    );
    const app = buildApp({ db, config });

    const stub: WebhookStub = { calls: [], behavior: "ok", statusQueue: [] };
    globalThis.fetch = (async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
      const headers = new Headers(init?.headers ?? undefined);
      const body = typeof init?.body === "string" ? init.body : "";
      stub.calls.push({ url, method: init?.method ?? "GET", headers, body });
      if (stub.statusQueue.length > 0) {
        return new Response("queued", { status: stub.statusQueue.shift()! });
      }
      if (stub.behavior === "throw") throw new Error("webhook target unreachable");
      if (typeof stub.behavior === "number") return new Response("nope", { status: stub.behavior });
      return new Response("ok", { status: 200 });
    }) as typeof fetch;

    const harness: TestHarness = {
      app,
      db,
      config,
      get: (path, extraHeaders = {}) => app.handle(new Request(`${BASE}${path}`, { headers: { authorization: `Bearer ${API_KEY}`, ...extraHeaders } })),
      handle: (path, init = {}) =>
        app.handle(
          new Request(`${BASE}${path}`, {
            ...init,
            headers: { authorization: `Bearer ${API_KEY}`, ...(init.headers as Record<string, string>) },
          }),
        ),
      raw: (path, init = {}) => app.handle(new Request(`${BASE}${path}`, init)),
      webhookStub: stub,
    };

    try {
      return await fn(harness);
    } finally {
      globalThis.fetch = realFetch;
      app.sweeper.stop();
      db.close();
    }
  })();
}

/** Asserts the single error envelope shape; returns the inner error object. */
export async function expectErrorEnvelope(
  response: Response,
  status?: number,
): Promise<{ code: string; message: string; hint: string; status: number }> {
  if (status !== undefined) expect(response.status).toBe(status);
  const body = (await response.json()) as { error?: { code?: string; message?: string; hint?: string; status?: number } };
  expect(body.error).toBeDefined();
  const err = body.error!;
  expect(typeof err.code).toBe("string");
  expect(err.code!.length).toBeGreaterThan(0);
  expect(typeof err.message).toBe("string");
  expect(err.message!.length).toBeGreaterThan(0);
  expect(typeof err.hint).toBe("string");
  expect(err.hint!.length).toBeGreaterThan(0); // never empty — LLM-friendly
  expect(err.status).toBe(response.status);
  if (status !== undefined) expect(err.status).toBe(status);
  return { code: err.code!, message: err.message!, hint: err.hint!, status: err.status! };
}

export function jsonHeaders(extra: Record<string, string> = {}): Record<string, string> {
  return { "content-type": "application/json", ...extra };
}

export async function putSlate(h: TestHarness, slateId: string, spec: unknown, extraHeaders: Record<string, string> = {}): Promise<Response> {
  return h.handle(`/api/slates/${slateId}`, { method: "PUT", headers: jsonHeaders(extraHeaders), body: JSON.stringify(spec) });
}

/** A minimal valid spec; fields can be overridden per test. */
export function minimalSpec(overrides: Record<string, unknown> = {}, slateId = "home"): Record<string, unknown> {
  return { id: slateId, version: 2, title: "Hello", children: [{ type: "text", text: "world" }], ...overrides };
}

export interface InteractionInput {
  seq: string;
  slateId: string;
  kind: string;
  elementId?: string;
  questionId?: string;
  optionId?: string;
  optionLabel?: string;
  itemId?: string;
  value?: string;
  clientAt?: string;
}

export function answer(seq: string, slateId: string, questionId: string, optionId: string, optionLabel = optionId): InteractionInput {
  return { seq, slateId, kind: "answer", elementId: questionId, questionId, optionId, optionLabel };
}

export async function postInteractions(h: TestHarness, interactions: InteractionInput[]): Promise<Response> {
  return h.handle("/api/device/interactions", { method: "POST", headers: jsonHeaders(), body: JSON.stringify({ interactions }) });
}
