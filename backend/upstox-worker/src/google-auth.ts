export interface GoogleAuthEnv {
  GOOGLE_CLIENT_ID: string;
  MGROW_ALLOWED_GOOGLE_EMAIL: string;
}

type GoogleClaims = {
  iss?: string;
  aud?: string | string[];
  exp?: number;
  iat?: number;
  email?: string;
  email_verified?: boolean;
};

type JwkSet = { keys?: JsonWebKey[] };
let cachedKeys: JsonWebKey[] | null = null;
let cacheExpiresAt = 0;

const GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
const CLOCK_SKEW_SECONDS = 60;

function decodeSegment(segment: string): Uint8Array {
  const normalized = segment.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "="));
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function parseJsonSegment<T>(segment: string): T {
  return JSON.parse(new TextDecoder().decode(decodeSegment(segment))) as T;
}

async function getGoogleKeys(): Promise<JsonWebKey[]> {
  if (cachedKeys && Date.now() < cacheExpiresAt) return cachedKeys;

  const response = await fetch(GOOGLE_JWKS_URL, {
    method: "GET",
    headers: { Accept: "application/json" },
  });
  if (!response.ok) throw new Error("Google signing keys unavailable");

  const body = (await response.json()) as JwkSet;
  if (!Array.isArray(body.keys) || body.keys.length === 0) {
    throw new Error("Google signing keys invalid");
  }

  const maxAge = response.headers.get("cache-control")?.match(/max-age=(\d+)/i);
  const ttlSeconds = Math.min(Math.max(Number(maxAge?.[1] ?? 300), 60), 3600);
  cachedKeys = body.keys;
  cacheExpiresAt = Date.now() + ttlSeconds * 1000;
  return cachedKeys;
}

export async function verifyGoogleIdToken(
  token: string,
  env: GoogleAuthEnv,
  nowSeconds = Math.floor(Date.now() / 1000),
): Promise<{ email: string } | null> {
  try {
    if (!env.GOOGLE_CLIENT_ID || !env.MGROW_ALLOWED_GOOGLE_EMAIL) return null;
    const parts = token.split(".");
    if (parts.length !== 3 || parts.some((part) => !part)) return null;

    const header = parseJsonSegment<{ alg?: string; kid?: string }>(parts[0]);
    if (header.alg !== "RS256" || !header.kid) return null;

    const claims = parseJsonSegment<GoogleClaims>(parts[1]);
    const issuerValid =
      claims.iss === "https://accounts.google.com" ||
      claims.iss === "accounts.google.com";
    const audienceValid = Array.isArray(claims.aud)
      ? claims.aud.includes(env.GOOGLE_CLIENT_ID)
      : claims.aud === env.GOOGLE_CLIENT_ID;
    if (
      !issuerValid ||
      !audienceValid ||
      typeof claims.exp !== "number" ||
      claims.exp <= nowSeconds ||
      typeof claims.iat !== "number" ||
      claims.iat > nowSeconds + CLOCK_SKEW_SECONDS ||
      claims.email_verified !== true ||
      typeof claims.email !== "string" ||
      claims.email.toLowerCase() !== env.MGROW_ALLOWED_GOOGLE_EMAIL.toLowerCase()
    ) return null;

    const jwk = (await getGoogleKeys()).find((key) => key.kid === header.kid);
    if (!jwk || jwk.kty !== "RSA") return null;

    const publicKey = await crypto.subtle.importKey(
      "jwk",
      jwk,
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["verify"],
    );
    const signedData = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
    const signature = decodeSegment(parts[2]);
    const signatureValid = await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      publicKey,
      signature,
      signedData,
    );
    return signatureValid ? { email: claims.email } : null;
  } catch {
    return null;
  }
}
