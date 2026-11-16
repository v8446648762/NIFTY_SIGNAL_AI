import test from "node:test";
import assert from "node:assert/strict";
import { handleRequest, extractValidCandles } from "../dist/src/index.js";

const emptyEnv = {
  UPSTOX_ANALYTICS_TOKEN: "",
  MGROW_APP_PIN: "",
  MGROW_SESSION_SECRET: "",
  QUOTE_LIMITER: { limit: async () => ({ success: true }) },
  LOGIN_LIMITER: { limit: async () => ({ success: true }) },
};

const configuredEnv = {
  ...emptyEnv,
  UPSTOX_ANALYTICS_TOKEN: "server-only-test-token",
  MGROW_APP_PIN: "83917462",
  MGROW_SESSION_SECRET: "test-session-secret-at-least-32-characters-long",
};

test("GET /health reports worker status without claiming broker connection", async () => {
  const response = await handleRequest(new Request("https://worker.example/health"), emptyEnv);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { service: "mgrow-upstox-proxy", status: "ready", brokerConnected: false });
  assert.equal(response.headers.get("cache-control"), "no-store, max-age=0");
});

test("non-GET requests to market endpoints are rejected", async () => {
  const response = await handleRequest(new Request("https://worker.example/v1/nifty/quote", { method: "POST" }), emptyEnv);
  assert.equal(response.status, 405);
});

test("unknown paths are rejected", async () => {
  const response = await handleRequest(new Request("https://worker.example/unknown"), emptyEnv);
  assert.equal(response.status, 404);
});

test("PIN login fails closed when secrets are missing", async () => {
  const response = await handleRequest(new Request("https://worker.example/v1/auth/login", {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ pin: "83917462" }),
  }), emptyEnv);
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "auth_secrets_not_configured" });
});

test("PIN login rejects an incorrect PIN", async () => {
  const response = await handleRequest(new Request("https://worker.example/v1/auth/login", {
    method: "POST", headers: { "content-type": "application/json", "CF-Connecting-IP": "192.0.2.1" },
    body: JSON.stringify({ pin: "11111111" }),
  }), configuredEnv);
  assert.equal(response.status, 401);
  assert.deepEqual(await response.json(), { error: "invalid_credentials" });
});

test("PIN login issues a short-lived session for the correct PIN", async () => {
  const response = await handleRequest(new Request("https://worker.example/v1/auth/login", {
    method: "POST", headers: { "content-type": "application/json", "CF-Connecting-IP": "192.0.2.2" },
    body: JSON.stringify({ pin: "83917462" }),
  }), configuredEnv);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(typeof body.token, "string");
  assert.equal(body.expiresIn, 43200);
});

test("quote endpoint rejects missing bearer credentials", async () => {
  const response = await handleRequest(new Request("https://worker.example/v1/nifty/quote"), configuredEnv);
  assert.equal(response.status, 401);
  assert.deepEqual(await response.json(), { error: "unauthorized" });
});

test("candles endpoint rejects malformed bearer credentials", async () => {
  const response = await handleRequest(new Request("https://worker.example/v1/nifty/candles", {
    headers: { Authorization: "Bearer not-a-valid-session" },
  }), configuredEnv);
  assert.equal(response.status, 401);
  assert.deepEqual(await response.json(), { error: "unauthorized" });
});

test("Upstox V3 candles are validated and sorted oldest first", () => {
  const payload = { status: "success", data: { candles: [
    ["2025-11-17T09:16:00+05:30", 25010, 25020, 25005, 25015, 1200, 0],
    ["2025-11-17T09:15:00+05:30", 25000, 25015, 24995, 25010, 900, 0],
  ] } };
  const result = extractValidCandles(payload);
  assert.equal(result?.length, 2);
  assert.equal(result?.[0].timestamp, "2025-11-17T09:15:00+05:30");
  assert.equal(result?.[1].close, 25015);
});

test("Upstox V3 candle validation rejects impossible OHLC values", () => {
  const payload = { status: "success", data: { candles: [
    ["2025-11-17T09:15:00+05:30", 25000, 24900, 25010, 25005, 900, 0],
  ] } };
  assert.equal(extractValidCandles(payload), null);
});
