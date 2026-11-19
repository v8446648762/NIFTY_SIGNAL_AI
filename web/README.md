# MGrow Private Web

This is a separate Cloudflare Pages web frontend. It does not change the Android application or the existing Upstox Worker.

## Important deployment/security requirements

1. Deploy the `web/` directory as the Cloudflare Pages build output (no framework build command required).
2. Before sharing or using the site, create a Cloudflare Zero Trust Access self-hosted application for the Pages hostname.
3. Set the Access policy to **Allow** only the owner's verified email address using a one-time PIN or identity provider. Do not use a client-side password screen as authentication.
4. Verify that Access protects both the page and `/api/*` routes.
5. Keep the existing broker token only as the Cloudflare Worker secret. This frontend never receives or stores the Upstox token.
6. Do not publish the Pages hostname until the Access gate has been tested in a signed-out/private browser session.

## Current scope

- Responsive MGrow dark dashboard with 1-minute and 5-minute completed-candle charts.
- Read-only data proxy to the existing fixed NIFTY Upstox Worker.
- Basic VWAP, EMA 9/21, RSI 14, and recent-candle support/resistance calculations.
- Explicit stale/error states and no automatic orders.

The Android Smart AI prediction engine has not yet been ported to JavaScript, so the web screen intentionally displays WAIT rather than inventing a GREEN/RED prediction. Market alerts and notification preferences are also not implemented in this first web milestone.

## Deployment status

Not deployed. Cloudflare Pages project/domain and Zero Trust Access policy must be configured and verified before a private URL can be issued. No paid service or plan change is assumed.
