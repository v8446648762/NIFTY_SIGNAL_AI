# MGrow Upstox read-only backend (scaffold)

This is an isolated Cloudflare Worker scaffold. It is not deployed and is not yet connected to the Android app.

## Endpoints
- `GET /health`: worker status only; does not contact Upstox.
- `GET /v1/nifty/quote`: fixed NIFTY 50 index LTP request using Upstox Analytics Token.

## Configure secrets (never commit token values)
Use Cloudflare Workers secrets:
```sh
npx wrangler secret put UPSTOX_ANALYTICS_TOKEN
npx wrangler secret put MGROW_CLIENT_KEY
```
Use a unique random client key. Never place the Upstox token in Android source, APK, GitHub, screenshots, or chat.

## Deploy only after reviewing Upstox terms and access
```sh
npx wrangler deploy
```

## Test
```sh
curl https://YOUR-WORKER.workers.dev/health
curl -H "x-mgrow-client-key: YOUR_CLIENT_KEY" https://YOUR-WORKER.workers.dev/v1/nifty/quote
```

## Security notes
- This worker is not deployed by this repository workflow.
- The Upstox credential remains server-side in Cloudflare Secrets.
- The client key is an additional gate, not a substitute for strong user authentication; any key embedded in a mobile app can potentially be extracted. Before production use, add a stronger access-control/rate-limiting layer and keep the endpoint private to the account owner.
- Responses are not cached. Only fixed GET routes are accepted; no arbitrary URL proxy, POST, order, portfolio, or account routes are exposed.
- Never log authorization headers or tokens.
- This scaffold does not itself validate exchange timestamps or produce trading signals. Android must reject missing/stale/invalid market data before analysis.
