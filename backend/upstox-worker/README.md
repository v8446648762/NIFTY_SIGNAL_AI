# MGrow Upstox read-only backend

This isolated Cloudflare Worker is a development scaffold. It is not deployed and is not connected to the Android app.

## Endpoints
- `GET /health`: worker status only; does not contact Upstox.
- `GET /v1/nifty/quote`: fixed NIFTY 50 index LTP request using Upstox Market Quote V3 using the Upstox Analytics Token. Requires a Google ID token in the Authorization Bearer header.

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
- Upstream request timeout, strict NIFTY quote payload validation, and the current V3 LTP endpoint.
- Cloudflare Rate Limiting binding is required for quote access; it keys requests to the verified owner email, uses a 30-request / 60-second window, returns HTTP 429 with Retry-After when exceeded, and fails closed if the limiter is unavailable.
- Cloudflare's built-in rate limit is approximate and counted independently per Cloudflare location; it is an abuse-control layer, not a strict global quota.
- Generic upstream error responses; authorization headers and tokens are not logged or forwarded.

## Cloudflare Git build configuration
For this monorepo, set the Workers Build **Root directory** to `backend/upstox-worker`.
- Build command: `npm run build`
- Deploy command: `npm run deploy`
- The Cloudflare Worker project name must exactly match `mgrow-upstox-readonly` from `wrangler.toml`.
- The rate-limit `namespace_id` is a positive integer unique within this Cloudflare account. The current value `1001` is a valid example if it is not already used by another rate-limit binding in this account; it is not a separate resource that must be provisioned.

Do not select Save and Deploy until the settings and server-side secrets have been reviewed and the remaining production blockers below are resolved.

## Production blockers
- The Android Google Credential Manager login flow is not connected.
- The Google OAuth client ID and allowed owner email still need to be configured securely.
- The API response receipt time is not the exchange timestamp. Add a verified exchange-time freshness check before AI analysis uses a quote.
- Review Upstox API terms and permissions for the intended personal research/data display use before live deployment.
- Monitoring and alerting are not configured.
- No live Upstox request has been verified from the deployed Worker.
- Do not deploy publicly until the remaining blockers are implemented and reviewed.

## Deployment status
Not deployed. No Android integration. No Main Dashboard files changed. No live-data test has been performed.
