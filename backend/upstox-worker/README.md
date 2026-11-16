# MGrow Upstox read-only backend

This isolated Cloudflare Worker is a development scaffold. It is not deployed and is not connected to the Android app.

## Endpoints
- `GET /health`: worker status only; does not contact Upstox.
- `GET /v1/nifty/quote`: fixed NIFTY 50 index LTP request using the Upstox Analytics Token. Requires a Google ID token in the Authorization Bearer header.

## Server-side configuration
Configure these values only in the Cloudflare Worker environment:
- Secret: `UPSTOX_ANALYTICS_TOKEN` (Upstox Analytics Token)
- Variable: `GOOGLE_CLIENT_ID` (OAuth client ID for the Android app)
- Variable: `MGROW_ALLOWED_GOOGLE_EMAIL` (the one Google account allowed to access this personal app)

Never commit token values or put them in Android source, APKs, screenshots, chat, or public GitHub content.

## Current safeguards
- Fixed GET routes only; no arbitrary proxy, order, portfolio, or account endpoints.
- Upstox token remains server-side; response caching disabled.
- Google ID tokens are checked server-side: RS256 signature against Google's published signing keys, issuer, exact client audience, expiry, issued-at time, verified email, and the configured owner email.
- Google signing keys are cached in the Worker isolate according to Google's cache headers.
- Upstream request timeout and strict NIFTY quote payload validation.
- Generic upstream error responses; authorization headers and tokens are not logged or forwarded.

## Production blockers
- Rate limiting, monitoring, and abuse controls are not yet implemented.
- The Android Google Credential Manager login flow is not connected.
- The Google OAuth client ID and allowed owner email still need to be configured securely.
- The API response receipt time is not the exchange timestamp. Add a verified exchange-time freshness check before AI analysis uses a quote.
- Review Upstox API terms and permissions for the intended personal research/data display use before live deployment.
- Do not deploy publicly until the remaining blockers are implemented and reviewed.

## Deployment status
Not deployed. No Android integration. No Main Dashboard files changed. No live-data test has been performed.
