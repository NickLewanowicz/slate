/**
 * Canonical JSON (sorted keys, minified) + content hashing.
 * The contentHash of a slate is sha256 of its canonical JSON — stable across
 * key order and whitespace, so identical re-pushes are idempotent.
 */

export function canonicalize(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(canonicalize);
  }
  if (value !== null && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const key of Object.keys(value as Record<string, unknown>).sort()) {
      out[key] = canonicalize((value as Record<string, unknown>)[key]);
    }
    return out;
  }
  return value;
}

export function canonicalJson(value: unknown): string {
  return JSON.stringify(canonicalize(value));
}

export function sha256Hex(input: string): string {
  return new Bun.CryptoHasher("sha256").update(input).digest("hex");
}

export function hmacSha256Hex(secret: string, input: string): string {
  return new Bun.CryptoHasher("sha256", secret).update(input).digest("hex");
}
