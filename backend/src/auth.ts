import { createHash, timingSafeEqual } from "node:crypto";
import { unauthorized } from "./errors";

/** Length-safe constant-time string comparison (hash both sides first). */
export function timingSafeEqualStrings(a: string, b: string): boolean {
  const ha = createHash("sha256").update(a).digest();
  const hb = createHash("sha256").update(b).digest();
  return timingSafeEqual(ha, hb);
}

export function parseBearerToken(headerValue: string | null): string | null {
  if (!headerValue) return null;
  const match = /^Bearer\s+(.+)$/.exec(headerValue.trim());
  return match && match[1] ? match[1].trim() : null;
}

const AUTH_HINT =
  'Send the header "Authorization: Bearer <SLATE_API_KEY>" with the exact key the server was started with. ' +
  'Scheme is case-sensitive ("Bearer", one space, then the key). Example: curl -H "Authorization: Bearer $SLATE_API_KEY" http://host:3000/api/ping';

/** Returns a 401 Response if the presented key does not match, else undefined (continue). */
export function checkAuth(
  request: Request,
  path: string,
  apiKey: string,
): Response | undefined {
  if (!path.startsWith("/api")) return undefined; // /health and / stay open
  const presented = parseBearerToken(request.headers.get("authorization"));
  if (presented === null || !timingSafeEqualStrings(presented, apiKey)) {
    return unauthorized("Missing or invalid API key.", AUTH_HINT);
  }
  return undefined;
}
