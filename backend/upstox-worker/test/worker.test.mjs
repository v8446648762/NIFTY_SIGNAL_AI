import test from "node:test";
import assert from "node:assert/strict";
import { handleRequest, extractValidCandles } from "../dist/src/index.js";

const emptyEnv = {
  UPSTOX_ANALYTICS_TOKEN: "",
  GOOGLE_CLIENT_ID: "",
  MGROW_ALLOWED_GOOGLE_EMAIL: "",
  QUOTE_LIMITER: { limit: async () => ({ success: true }) },
};

test("GET /health reports worker status without claiming broker connection", async () => {
  const response = await handleRequest(new Request("https://worker.example/health"), emptyEnv);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    service: "mgrow-upstox-proxy",
    status: "ready",
    brokerConnected: false,
  });
  assert.equal(response.headers.get("cache-control"), "no-store, max-age=0");
});

test("non-GET requests are rejected", async () => {
  const response = await handleRequest(
    new Request("https://worker.example/v1/nifty/quote", { method: "POST" }),
    emptyEnv,
  );
  assert.equal(response.status, 405);
  assert.deepEqual(await response.json(), { error: "method_not_allowed" });
});

test("unknown paths are rejected", async () => {
  const response = await handleRequest(new Request("https://worker.example/unknown"), emptyEnv);
  assert.equal(response.status, 404);
  assert.deepEqual(await response.json(), { error: "not_found" });
});

test("quote endpoint fails closed when server secrets are missing", async () => {
  const response = await handleRequest(
    new Request("https://worker.example/v1/nifty/quote"),
    emptyEnv,
  );
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "server_secrets_not_configured" });
});

test("quote endpoint rejects missing bearer credentials", async () => {
  const configuredEnv = {
    ...emptyEnv,
    UPSTOX_ANALYTICS_TOKEN: "server-only-test-token",
    GOOGLE_CLIENT_ID: "test-client-id",
    MGROW_ALLOWED_GOOGLE_EMAIL: "owner@example.com",
  };
  const response = await handleRequest(
    new Request("https://worker.example/v1/nifty/quote"),
    configuredEnv,
  );
  assert.equal(response.status, 401);
  assert.deepEqual(await response.json(), { error: "unauthorized" });
});

test("quote endpoint rejects malformed bearer tokens", async () => {
  const configuredEnv = {
    ...emptyEnv,
    UPSTOX_ANALYTICS_TOKEN: "server-only-test-token",
    GOOGLE_CLIENT_ID: "test-client-id",
    MGROW_ALLOWED_GOOGLE_EMAIL: "owner@example.com",
  };
  const response = await handleRequest(
    new Request("https://worker.example/v1/nifty/quote", {
      headers: { Authorization: "Bearer not-a-valid-jwt" },
    }),
    configuredEnv,
  );
  assert.equal(response.status, 401);
  assert.deepEqual(await response.json(), { error: "unauthorized" });
});


test("candles endpoint is protected by Google authentication", async () => {
  const configuredEnv = {
    ...emptyEnv,
    UPSTOX_ANALYTICS_TOKEN: "server-only-test-token",
    GOOGLE_CLIENT_ID: "test-client-id",
    MGROW_ALLOWED_GOOGLE_EMAIL: "owner@example.com",
  };
  const response = await handleRequest(
    new Request("https://worker.example/v1/nifty/candles"),
    configuredEnv,
  );
  assert.equal(response.status, 401);
  assert.deepEqual(await response.json(), { error: "unauthorized" });
});

test("Upstox V3 candles are validated and sorted oldest first", () => {
  const payload = {
    status: "success",
    data: {
      candles: [
        ["2026-11-17T09:16:00+05:30", 25010, 25020, 25005, 25015, 1200, 0],
        ["2026-11-17T09:15:00+05:30", 25000, 25015, 24995, 25010, 900, 0],
      ],
    },
  };
  const result = extractValidCandles(payload);
  assert.equal(result?.length, 2);
  assert.equal(result?.[0].timestamp, "2026-11-17T09:15:00+05:30");
  assert.equal(result?.[1].close, 25015);
});

test("Upstox V3 candle validation rejects impossible OHLC values", () => {
  const payload = {
    status: "success",
    data: {
      candles: [["2026-11-17T09:15:00+05:30", 25000, 24900, 25010, 25005, 900, 0]],
    },
  };
  assert.equal(extractValidCandles(payload), null);
});
