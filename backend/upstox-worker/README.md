# MGrow live Upstox data worker (read-only)

This branch adds the server-side NIFTY option-chain and 1m/5m candle routes. It is not deployed and is not yet connected to the Android UI.

## Routes
- `GET /health` is public and reports worker readiness only.
- `GET /v1/nifty/option-chain?expiry_date=YYYY-MM-DD` returns normalized CE/PE OI, change in OI (OI minus previous OI), volume, LTP, IV, delta, bid/ask, spot and PCR.
- `GET /v1/nifty/candles` returns independent 1m and 5m NIFTY candles.

## Authentication and secrets
All data routes require a Google ID token in `Authorization: Bearer <Google-ID-token>`. The worker verifies RS256 signature, issuer, audience, expiry, verified email and the configured owner email. The Upstox token is read only from the Cloudflare Worker secret `UPSTOX_ANALYTICS_TOKEN`; never put it in Android, GitHub, screenshots or chat.

Configure Worker variables `GOOGLE_CLIENT_ID` and `MGROW_ALLOWED_GOOGLE_EMAIL`, and secret `UPSTOX_ANALYTICS_TOKEN`. Configure the `QUOTE_LIMITER` binding as declared in wrangler.toml.

## Not yet complete
- Android Google Credential Manager login / ID-token handoff is not wired to the app.
- Android MarketDataFeed adapter and option-chain UI mapping are not wired.
- No Worker deployment, Cloudflare secret setup, live Upstox request, or APK build has been performed.
- Verify Upstox API access and expiry-date availability before deployment.
- Build and test this Worker before deployment; do not deploy until the Android authentication flow is connected and reviewed.

No order-placement endpoint exists. This service is read-only.
