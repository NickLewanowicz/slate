/** Small HTTP/request helpers shared by route modules. */

export function nowIso(): string {
  return new Date().toISOString();
}

export function isPlainObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

/** Content-Length pre-check: reject oversized bodies BEFORE buffering them into memory. */
export function contentLengthTooLarge(request: Request, limitBytes: number): boolean {
  const raw = request.headers.get("content-length");
  if (raw === null) return false;
  const value = Number(raw);
  return Number.isFinite(value) && value > limitBytes;
}

/** Parses the request body as JSON; returns {ok:false, error} on bad JSON. */
export async function parseJsonBody(request: Request): Promise<{ ok: true; body: unknown } | { ok: false; raw: string | null }> {
  let raw: string | null = null;
  try {
    raw = await request.text();
  } catch {
    return { ok: false, raw: null };
  }
  if (raw === null || raw.trim() === "") {
    return { ok: false, raw };
  }
  try {
    return { ok: true, body: JSON.parse(raw) };
  } catch {
    return { ok: false, raw };
  }
}

/** Positive-integer query param parse. Returns {ok:false} on garbage. */
export function parsePositiveInt(raw: string | null | undefined): { ok: true; value: number } | { ok: false } {
  if (raw === undefined || raw === null || raw.trim() === "") return { ok: false };
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 1) return { ok: false };
  return { ok: true, value };
}

/** Non-negative-integer query param parse (0 allowed — e.g. the `since` cursor start). */
export function parseNonNegativeInt(raw: string | null | undefined): { ok: true; value: number } | { ok: false } {
  if (raw === undefined || raw === null || raw.trim() === "") return { ok: false };
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 0) return { ok: false };
  return { ok: true, value };
}
