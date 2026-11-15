# MGrow Security Policy

## Current app scope
- Analysis and alerts only. No broker order placement or automatic trading.
- No live market-data provider is connected in the current pre-integration build.
- Do not treat sample chart previews as live market data.

## Android app safeguards
- Android backup is disabled in the app manifest.
- Cleartext HTTP traffic is blocked; network security trusts system certificate authorities only.
- Keep Android permissions limited to those required for internet connectivity, network state, and notifications.
- Keep exported Android components limited to the launcher activity unless a reviewed feature requires otherwise.
- Do not log credentials, access tokens, personal data, or full market-provider authorization headers.

## Future market-data integration requirements
- Never embed broker API keys, client secrets, refresh tokens, or access tokens in the APK, source code, assets, or public repository.
- Use a secure backend/token exchange where required by the provider; store secrets only in an appropriately secured server-side secret store.
- Use HTTPS/TLS end-to-end and validate server certificates. Do not add certificate pinning until provider domains, rotation, and recovery are designed.
- Validate symbol, timestamp, candle values, ordering, duplicates, and feed freshness before analysis. A stale or invalid feed must not produce a live signal.
- Request only read-only market-data permissions. Do not add order APIs, order placement, or automatic execution.
- Do not add paid services, hosting, or subscriptions without explicit user approval.

## Verification
- Run unit tests and build the APK for every security-related change.
- Perform an independent APK malware scan and a release-build review before distributing a production APK. A successful build is not proof that an APK is malware-free.
