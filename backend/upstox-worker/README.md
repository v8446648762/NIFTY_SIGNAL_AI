# MGrow Upstox read-only backend

Cloudflare Worker for automated, fixed-instrument NIFTY 50 market data. The Android app calls this Worker directly without Google sign-in. The Upstox Analytics Token stays only in the Worker secret store.

## Endpoints
- `GET /health`: worker health only; does not contact Upstox.
- `GET /v1/nifty/quote`: fixed NIFTY 50 quote via Upstox Full Market Quotes V3.
- `GET /v1/nifty/candles`: fixed NIFTY 50 1-minute and 5-minute intraday candles via Upstox Historical Candle V3.

## Cloudflare configuration
- Secret: `UPSTOX_ANALYTICS_TOKEN` (set with `npx wrangler secret put UPSTOX_ANALYTICS_TOKEN`; never commit it).
- Rate limiter binding: `QUOTE_LIMITER`, 30 requests / 60 seconds per Cloudflare client IP.
- Root directory: `backend/upstox-worker`.
- Build command: `npm run build`.
- Deploy command: `npm run deploy`.
- Worker name must be `mgrow-upstox-readonly`.

These endpoints expose only fixed public NIFTY market data, not account or portfolio information. Because the app must work without an interactive login, endpoints are unauthenticated and protected by Cloudflare edge-IP rate limiting. Do not add arbitrary instrument parameters, broker account routes, order routes, or token-returning responses. The Cloudflare edge supplies `CF-Connecting-IP`; requests without it fail closed.

## Safeguards
- Upstox token is server-side only; no broker credential is sent to the app.
- Fixed GET routes only; no arbitrary proxy, orders, portfolios, or account endpoints.
- Responses are no-store; upstream timeout and strict OHLCV / quote validation are enabled.
- Quote timestamps must be no more than 60 seconds old or 5 seconds in the future.
- Rate limiting fails closed if the binding is unavailable.
- The edge rate limit is approximate and per Cloudflare location; it is an abuse-control layer, not a strict global quota.

## Deployment and verification
A successful Android build does not prove live data. Deploy the Worker with the secret configured, then verify `/health`, `/v1/nifty/quote`, and `/v1/nifty/candles` from the deployed Worker before claiming the feed is live. Confirm Upstox API permissions and terms for personal market-data display.
