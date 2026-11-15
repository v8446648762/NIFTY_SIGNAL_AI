# MGrow — Android NIFTY Analysis App

MGrow is a personal NIFTY analysis dashboard. The current Android build is a **pre-integration build**: the dashboard, analysis structure, appearance settings, feature guide, paper-trading practice screen, and notification preferences are present, but no broker market-data feed is connected yet.

## Current status

- The app has a market-data interface (`MarketDataFeed`) ready for a future data adapter.
- Android Internet and network-state permissions are declared for the future secure connection.
- No live NIFTY price, option-chain data, or broker feed is currently supplied.
- Sample chart visuals are previews only. Do not treat them as current market prices or signals.
- Indicator and signal calculations must be used only after validated live candles are received.
- Notification preferences and a phone test notification are available. Actual market-triggered alerts are not active until live data and alert rules are integrated.
- Some planned panels, including option-chain analytics and the risk score, still need their live-data calculations and validation.
- Paper trading is manual practice only. Its current session history is temporary and no broker order is sent.
- No broker credentials or API secrets should be stored in the APK or public repository.

## Safety and trading scope

- MGrow is for analysis and alerts only.
- It does not place trades.
- No order should ever be placed automatically.
- Any future order-related feature would require a separate explicit user confirmation; the current project does not implement order placement.

## Build Android APK

1. Push changes to the `main` branch.
2. Open the repository's **Actions** tab.
3. Select **Build Android APK** and open the latest successful run.
4. Download the `NIFTY_SIGNAL_AI-demo-apk` artifact.

The APK must pass the automated unit-test and build workflow before it is treated as a successful build.
