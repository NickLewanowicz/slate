/**
 * Single error envelope for every error path:
 *   { error: { code, message, hint, status } }
 * `hint` is written for an LLM: valid values, closest-intent guess, corrected snippet.
 */

export interface ErrorEnvelope {
  error: {
    code: string;
    message: string;
    hint: string;
    status: number;
  };
}

export function errorResponse(status: number, code: string, message: string, hint: string): Response {
  const body: ErrorEnvelope = { error: { code, message, hint: hint || "See schema/api.md for the endpoint contract." , status } };
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

export function badRequest(code: string, message: string, hint: string): Response {
  return errorResponse(400, code, message, hint);
}

export function validationError(message: string, hint: string): Response {
  return errorResponse(400, "validation_error", message, hint);
}

export function payloadTooLarge(message: string, hint: string): Response {
  return errorResponse(400, "payload_too_large", message, hint);
}

export function unauthorized(message: string, hint: string): Response {
  return errorResponse(401, "unauthorized", message, hint);
}

export function notFound(message: string, hint: string): Response {
  return errorResponse(404, "not_found", message, hint);
}

export function internalError(message: string, hint: string): Response {
  return errorResponse(500, "internal_error", message, hint);
}

export function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...headers },
  });
}
