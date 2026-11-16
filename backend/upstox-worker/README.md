# MGrow Upstox read-only backend

This isolated Cloudflare Worker is a development scaffold. It is not deployed and is not connected to the Android app.

## Endpoints
- `GET /health`: worker status only; does not contact Upstox.
- `GET /v1/nifty/quote`: fixed NIFTY 50 index LTP request using the Upstox Analytics Token.

## Secrets
Configure secrets only in the Cloudflare Worker environment:
```sh
npx wrangler secret put UPSTOX_ANALYTICS_TOKEN
npx wrangler secret put MGROW_CLIENT_KEY
```
Never commit token values or put them in Android source, APKs, screenshots, chat, or public GitHub content.

## Current safeguards
- Fixed GET routes only; no arbitrary proxy, order, portfolio, or account endpoints.
- Server-side Upstox token; response caching disabled.
- Upstream request timeout and strict NIFTY quote payload validation.
- Generic upstream error responses; authorization headers and tokens are not logged or forwarded.

## Important production blockers
- The shared client-key header is only a temporary development gate. Any static key embedded in an APK can be extracted; it is NOT production-grade user authentication.
- Do not deploy publicly until account-owner authentication, abuse/rate limiting, and operational monitoring are implemented and reviewed.
- The API response's receipt time is not the exchange timestamp. A successful response alone does not prove that the quote is fresh. Add a verified exchange-time freshness check before allowing the app's AI engine to use the quote.
- Review Upstox API terms and permissions for the intended personal research/data display use before live deployment.

## Deployment status
Not deployed. No Android integration. No Main Dashboard files changed. No live-data test has been performed.
