# MGrow Upstox read-only backend

This Worker serves only fixed NIFTY 50 market data to the Build 157 Android adapter. It is intentionally read-only: no order, portfolio, holdings, funds, or account endpoints exist.

## Endpoints
- `GET /health`: Worker readiness only; it does not verify Upstox connectivity.
- `GET /v1/nifty/candles`: Upstox V3 intraday 5-minute candles plus optional 1-minute candles.
- `GET /v1/nifty/quote`: fixed NIFTY 50 quote endpoint for diagnostics.

## Public access and safeguards
The market endpoints do not require Google login or app authentication, as approved for this personal read-only integration. Anyone who discovers the URL can request the same fixed NIFTY 50 data. Cloudflare rate limiting is keyed by the connecting client IP (30 requests per 60 seconds). Cloudflare rate limits are approximate and distributed by data-center location, so they are an abuse-control measure, not a hard global quota. Monitor usage and disable the Worker if unexpected traffic appears.

Only GET requests and fixed NIFTY 50 instrument paths are accepted. The caller cannot select arbitrary instruments, timeframes, or Upstox routes. Responses use no-store headers. Upstream requests have timeouts and strict payload validation. The candle response is cached inside each Worker isolate for up to 55 seconds to reduce upstream requests. A failed optional 1-minute request does not interrupt the 5-minute feed.

## Configure the broker token
In the Cloudflare dashboard, open Worker `mgrow-upstox-readonly` → Settings → Variables and Secrets. Add `UPSTOX_ANALYTICS_TOKEN` as an encrypted **Secret**, not a plain variable. Never commit the token or put it in Android source, APKs, screenshots, chat, or public GitHub content.

## Deploy
Worker root directory: `backend/upstox-worker`
- Build/test: `npm ci && npm test`
- Deploy: `npm run deploy`

Before deploying, confirm the Cloudflare rate-limit binding named `QUOTE_LIMITER` is supported for this Worker and its namespace ID is unique in the account. Configure the secret first. Deployment is a separate Cloudflare action and is not performed by the Android GitHub build.

## Android integration
Build 157 already has `UpstoxWorkerMarketDataFeed`, which polls the configured HTTPS Worker endpoint every 20 seconds, validates provider and completed candles, and marks stale data rather than generating signals from stale candles. The adapter endpoint must match the deployed Worker URL. No broker token is present in Android.

## Trading scope
Analysis and alerts only. No automatic order placement. Validate market-data entitlement and Upstox API terms before production use. A successful CI build does not prove the Worker is deployed or receiving live Upstox data.
