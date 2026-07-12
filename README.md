# Stock SMA

An Android app that tracks stock and ETF prices, computes a **simple moving average (SMA)** over a configurable number of days entirely on the device, and sends a notification when the price crosses that average — optionally only beyond a configurable percentage threshold.

No backend required: fetching, storage (Room), scheduling (WorkManager) and notifications all run locally on the phone.

## Features

- **Watchlist** — add any stock or ETF supported by the selected data source, remove at any time.
- **Per-instrument settings** — SMA window in days and a notification threshold in percent (0 % = notify on any crossing).
- **SMA seed value** — if your data source provides no history, enter today's average manually as a starting point. It is blended into the calculation (`((window − n) × seed + sum of n real closes) / window`) and ignored automatically once the window is filled with real data. The detail screen shows whether the SMA is still seed-based.
- **Notifications** — state the ticker, current price, SMA value, direction (above/below) and percentage distance. One notification per crossing event; re-armed when the price crosses back, or after an optional cooldown.
- **Charts** — price line with the SMA overlaid, freely adjustable horizon (7–365 days via slider, with 1M/3M/6M/1Y quick presets), colors adapt to the theme.
- **Adaptive UI** — list/detail side-by-side on tablets and wide screens, single-pane on phones.
- **Themes** — Light, Dark, or follow the Android system setting (default). Applies immediately.

## Data sources

Selectable in Settings. Switching sources never deletes stored price history.

| Source | Key needed | Notes |
|---|---|---|
| **Stooq** (default) | No | Daily OHLC via CSV. Symbols use suffixes: `aapl.us`, `sap.de`, `vwce.de`. No search API — enter the ticker directly. Unpublished daily request cap; end-of-day data. |
| **Yahoo Finance** | No | Unofficial chart endpoint; full symbol search. May be rate-limited or change without notice — the app fails gracefully and shows the error per instrument. |
| **Alpha Vantage** | Free key | ~25 requests/day. Get a key at <https://www.alphavantage.co/support/#api-key> and paste it in Settings (stored encrypted). |
| **Twelve Data** | Free key | ~800 requests/day. Key at <https://twelvedata.com/pricing>. |
| **Custom** | Optional | Bring your own endpoint — see below. |

Requests are spaced out per provider to respect free-tier rate limits; fetch errors are shown per instrument in the watchlist and detail screen.

### Custom data source

Configure in Settings:

1. **History URL template** with placeholders `{symbol}` and `{apikey}`.
2. **Format**: CSV (date/close column indices, optional header skip) or JSON (dot-separated path to the array, date/close field names).
3. **Date format** pattern, e.g. `yyyy-MM-dd` or `dd.MM.yyyy`.
4. Enter a **test symbol** and press **Test** — the app performs a real request and reports how many data points it could parse before you save.

**Worked example (Stooq as a “custom” source):**

- History URL: `https://stooq.com/q/d/l/?s={symbol}&i=d`
- Format: CSV, date column `0`, close column `4`, skip header ✔
- Date format: `yyyy-MM-dd`
- Test symbol: `aapl.us`

## Building

### Android Studio (recommended)

1. Install [Android Studio](https://developer.android.com/studio) (Ladybug or newer).
2. **File → Open** the project folder. Studio provisions the correct SDK and Gradle automatically.
3. **Build → Build APK(s)**, or press **Run** with a device/emulator selected.

### Command line

The repository does not include the Gradle wrapper JAR. Generate it once with a local Gradle installation (8.9+):

```bash
gradle wrapper --gradle-version 8.9
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # unit tests (SMA, crossing logic, parsers)
```

Requires JDK 17.

## Installing on a phone

1. On the phone: **Settings → About phone → tap “Build number” 7×** to enable developer options, then enable **USB debugging**.
2. Connect via USB and either press **Run** in Android Studio, or:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

3. On first launch, allow the notification permission (Android 13+).
4. Recommended: exclude the app from battery optimization (Settings → Apps → Stock SMA → Battery → Unrestricted) so background fetches run reliably.

## Background scheduling — known limitations

- Android enforces a **15-minute minimum** for periodic background work; “24× per day” is the practical ceiling in this app.
- **Doze mode** and battery optimization can delay fetches by minutes to hours. Crossing notifications are therefore *best-effort*, not real-time.
- The default source (Stooq) provides end-of-day data — fetching more often than a few times per day mostly re-reads the same close. Yahoo or Twelve Data are better suited for intraday checks.

## Project layout

```
app/src/main/java/com/stocksma/
├─ domain/Sma.kt          # pure SMA + crossing/re-arm logic (unit-tested)
├─ data/Db.kt             # Room entities + DAO (instruments, price history)
├─ data/Providers.kt      # provider abstraction, 4 built-ins, custom source, parsers
├─ data/Settings.kt       # DataStore settings + encrypted API keys
├─ work/FetchWorker.kt    # periodic fetch, SMA check, notifications, scheduling
├─ ui/Ui.kt               # theme, adaptive navigation, screens, canvas chart
└─ Main.kt                # Application + MainActivity
```

## Disclaimer

Price data comes from free third-party endpoints and may be delayed, incomplete or unavailable. This app is a technical tool, not investment advice.
