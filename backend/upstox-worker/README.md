# MGrow read-only Upstox data worker

This worker supplies the Android app's NIFTY 50 candles and option chain. It does not place orders.

## Routes
- `GET /health` — worker health only.
- `GET /v1/nifty/expiries` — upcoming NIFTY option expiries from Upstox.
- `GET /v1/nifty/option-chain?expiry_date=YYYY-MM-DD` — CE/PE OI, change in OI, volume, LTP, IV, delta, bid/ask and strike.
- `GET /v1/nifty/candles` — independent 5-minute and 1-minute NIFTY candles.

## Security
- Set `UPSTOX_ANALYTICS_TOKEN` only as a Cloudflare Worker secret. It is never returned to Android.
- Data routes are read-only and rate-limited by Cloudflare client IP using `QUOTE_LIMITER`.
- No Google login, app login, fingerprint-to-broker authentication, or order endpoint is added.
- Because the read-only data routes are public, keep the Cloudflare IP rate limiter enabled and monitor its usage.

## Deploy configuration
1. Install dependencies and run `npm run build`.
2. Configure the `QUOTE_LIMITER` binding from `wrangler.toml`.
3. Add the Upstox access token as a Worker secret named `UPSTOX_ANALYTICS_TOKEN`.
4. Deploy the worker and note its exact `workers.dev` URL.
5. Supply that URL as the Android build environment variable `MGROW_API_BASE_URL`.

## Android connection
The Android adapter polls the worker every 30 seconds, discovers the nearest upcoming expiry, and maps the response into the existing Option Chain and Smart AI Analyser screens. No broker credential is stored in the APK.

A worker deployment, Cloudflare secret configuration, live API validation and APK build are separate operational steps; this repository change does not claim they have happened.
