import { describe, expect, test } from "bun:test";
import { checkAuth, parseBearerToken, timingSafeEqualStrings } from "../src/auth";
import { expectErrorEnvelope, jsonHeaders, withHarness, minimalSpec, putSlate, BASE, API_KEY } from "./helpers";

const AUTHED_PATHS: Array<[string, RequestInit]> = [
  ["/api/ping", {}],
  ["/api/slates", {}],
  ["/api/slates/home", {}],
  ["/api/slates/home", { method: "PUT", headers: jsonHeaders(), body: JSON.stringify(minimalSpec()) }],
  ["/api/slates/home", { method: "DELETE" }],
  ["/api/slates/home/responses", {}],
  ["/api/slates/home/responses", { method: "DELETE" }],
  ["/api/device/slates", {}],
  ["/api/device/slates/home", {}],
  ["/api/device/interactions", { method: "POST", headers: jsonHeaders(), body: '{"interactions":[]}' }],
];

describe("auth", () => {
  test("every /api route requires a Bearer key (401 envelope)", async () => {
    await withHarness(async (h) => {
      for (const [path, init] of AUTHED_PATHS) {
        const r = await h.raw(path, init);
        const err = await expectErrorEnvelope(r, 401);
        expect(err.code).toBe("unauthorized");
        expect(err.hint).toContain("Authorization: Bearer");
      }
    });
  });

  test("wrong key, wrong scheme, and empty key are rejected with constant-time compare", async () => {
    await withHarness(async (h) => {
      for (const header of ["Bearer wrong-key", "Basic dXNlcjpwYXNz", "Bearer", "bearer test-key", "Token test-key"]) {
        const r = await h.app.handle(new Request(`${BASE}/api/ping`, { headers: { authorization: header } }));
        expect(r.status, `header "${header}" should be rejected`).toBe(401);
      }
    });
  });

  test("correct key authenticates ping", async () => {
    await withHarness(async (h) => {
      const r = await h.get("/api/ping");
      expect(r.status).toBe(200);
      const body = (await r.json()) as { ok: boolean; serverTime: string; version: string };
      expect(body.ok).toBe(true);
      expect(Number.isFinite(Date.parse(body.serverTime))).toBe(true);
      expect(body.version.length).toBeGreaterThan(0);
    });
  });

  test("timingSafeEqualStrings: equal only for equal strings", () => {
    expect(timingSafeEqualStrings("a", "a")).toBe(true);
    expect(timingSafeEqualStrings("a", "b")).toBe(false);
    expect(timingSafeEqualStrings("", "")).toBe(true);
    expect(timingSafeEqualStrings("short", "a-much-longer-string")).toBe(false);
  });

  test("parseBearerToken", () => {
    expect(parseBearerToken(null)).toBeNull();
    expect(parseBearerToken("")).toBeNull();
    expect(parseBearerToken("Bearer abc")).toBe("abc");
    expect(parseBearerToken("bearer abc")).toBeNull(); // case-sensitive scheme
    expect(parseBearerToken("Bearer  spaced  ")).toBe("spaced");
    expect(parseBearerToken("Bearer")).toBeNull();
  });

  test("checkAuth leaves non-/api paths open", () => {
    const request = new Request(`${BASE}/health`);
    expect(checkAuth(request, "/health", API_KEY)).toBeUndefined();
    expect(checkAuth(request, "/", API_KEY)).toBeUndefined();
    const blocked = checkAuth(request, "/api/ping", API_KEY);
    expect(blocked).toBeDefined();
    expect(blocked!.status).toBe(401);
  });
});
