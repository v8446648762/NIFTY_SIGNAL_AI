# Isolated Upstox WebSocket diagnostic Worker

This folder is separate from the production MGrow Worker and Android app. Do not connect it to the production route or modify the existing Worker.

## Deploy as a NEW Worker

1. In Cloudflare Dashboard, open **Workers & Pages**.
2. Select **Create application** -> **Create Worker** and name it `mgrow-upstox-ws-diagnostic`.
3. Open the new Worker's editor and replace its code with `upstox-ws-diagnostic.js` from this folder.
4. In the new Worker's **Settings -> Variables and Secrets**, add two entries of type **Secret**:
   - `UPSTOX_ANALYTICS_TOKEN` = the same Upstox read-only Analytics Token already used by MGrow (enter it directly in Cloudflare; do not send it in chat or GitHub).
   - `TEST_ACCESS_KEY` = create a separate long random password for this diagnostic endpoint. Do not reuse your Upstox token.
5. Deploy this new Worker only. Do not edit or deploy the existing production Worker.

## Run the diagnostic

Send a GET request to:

`https://<new-worker-subdomain>.workers.dev/diagnostic`

with header:

`x-test-key: <your TEST_ACCESS_KEY>`

The response reports authorization, WebSocket connection, whether subscription was sent, and the number of incoming frames observed over 15 seconds. It intentionally does not log the token, authorized WebSocket URL, or market-data payloads. It counts frames only; it does not decode prices per instrument.

The endpoint is protected by the separate `TEST_ACCESS_KEY`. Keep it private. Delete this diagnostic Worker and its secrets after testing if no longer needed.

## Safety boundaries

- No production Worker, production token, Android app, or live-feed integration code is modified by this diagnostic.
- No order-placement API or Buy/Sell execution is used.
- The test asks Upstox for LTPC updates for NIFTY 50, NIFTY Bank, and NIFTY Financial Services.
- A received frame is not proof that each requested instrument delivered a fresh price; the current diagnostic is a connectivity/frame-count test only.
