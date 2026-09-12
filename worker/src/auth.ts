import type { Env } from "./ns";

export const VERTREK_KEY_HEADER = "X-Vertrek-Key";

export type AuthResult =
  | { ok: true }
  | { ok: false; status: 401; code: "UNAUTHORIZED"; message: string }
  | { ok: false; status: 500; code: "SERVER_MISCONFIGURED"; message: string };

/**
 * Every request to /next must present X-Vertrek-Key matching the
 * VERTREK_KEY secret, compared in constant time. If the secret itself
 * isn't configured, this fails closed (500) rather than letting every
 * request through.
 */
export function checkVertrekKey(request: Request, env: Env): AuthResult {
  if (!env.VERTREK_KEY) {
    return {
      ok: false,
      status: 500,
      code: "SERVER_MISCONFIGURED",
      message: "VERTREK_KEY is not configured on this Worker.",
    };
  }

  const provided = request.headers.get(VERTREK_KEY_HEADER) ?? "";

  const encoder = new TextEncoder();
  const providedBytes = encoder.encode(provided);
  const expectedBytes = encoder.encode(env.VERTREK_KEY);

  // Never short-circuit on length: an early return there leaks the
  // secret's length through response timing. When lengths differ,
  // compare the provided value against itself instead (always
  // constant-time, never reveals anything about the real secret).
  const lengthsMatch = providedBytes.byteLength === expectedBytes.byteLength;
  const isEqual = lengthsMatch
    ? crypto.subtle.timingSafeEqual(providedBytes, expectedBytes)
    : !crypto.subtle.timingSafeEqual(providedBytes, providedBytes);

  if (!isEqual) {
    return {
      ok: false,
      status: 401,
      code: "UNAUTHORIZED",
      message: `Missing or invalid ${VERTREK_KEY_HEADER} header.`,
    };
  }

  return { ok: true };
}
