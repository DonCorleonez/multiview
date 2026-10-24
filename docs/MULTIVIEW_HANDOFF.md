# MULTIVIEW_HANDOFF.md — Xtream Live Multiviewer for Google TV Streamer 4K

Prepared by Claude Code for Codex · 2026-10-24 · Owner: Oggie

This is the **only** spec. Scope is deliberately small: a full-screen live-TV multiviewer (1–4 tiles),
one Xtream account per tile. Anything not listed here is out of scope.

Hard limits for Codex:
- Never connect to, install on, or change the device. Device sessions are run by Claude Code with the
  owner, after approval.
- Never commit credentials, server URLs, IPs, serials, ADB keys, or prebuilt APKs.
- No DRM/certification bypass, no root.

---

## 1. Target device

| Item | Value |
|---|---|
| Device | Google TV Streamer 4K (2024) — MediaTek MT8696, 4 GB RAM, Android TV OS 14, Play-certified |
| Output | HDMI to Hisense 55", 1920×1080 UI (dp-scaled), video up to source resolution |
| Input | Google TV remote: D-pad, OK, Back, Home, volume, mute. **No number keys, no Menu key, no Play/Pause guaranteed.** Design for D-pad + OK + Back + long-press OK only |
| Install path | `adb install` from Termux (Galaxy Tab). ADB installs are exempt from Indonesia's developer-verification rule (in force since 2026-09-30). No Play Store distribution |

Unknown until measured (Milestone M4): how many concurrent HW video decoders at 1080p H.264 / HEVC /
2160p. **Do not hard-code a tile limit**; handle decoder failure per tile (§6.4).

## 2. What the user sees

### 2.1 Main screen (the only screen during normal use)
- Black background, full screen, no status bar, no clock, no logos, no EPG.
- Tiles show video only. The **highlighted tile** has a 4 dp white border. Nothing else is drawn,
  except short state text inside a tile when it isn't playing:
  `Pilih channel` · `Menyambung…` · `Menyambung ulang…` · `Akun belum diisi` · `Decoder penuh` ·
  `Koneksi ditolak provider`.
- Screen stays on while the activity is visible (`FLAG_KEEP_SCREEN_ON`).

### 2.2 Layouts — fixed slot positions on a 2×2 grid

Each slot always sits in the same place, so muscle memory works.

```
Layout 4          Layout 3          Layout 2                 Layout 1
┌────┬────┐       ┌────┬────┐       ┌────┬────┐               ┌─────────┐
│ S1 │ S2 │       │ S1 │ S2 │       │    │    │               │         │
├────┼────┤       ├────┼────┤       │ S1 │ S2 │ (16:9 each,   │ highl.  │
│ S3 │ S4 │       │ S3 │ ▓▓ │       │    │    │  centred      │  slot   │
└────┴────┘       └────┴────┘       └────┴────┘  vertically)  └─────────┘
```

- Layout 4: S1–S4. Layout 3: S1–S3, bottom-right black. Layout 2: S1 and S2 side by side, each 960×540,
  vertically centred. Layout 1: the **currently highlighted slot** full screen (acts as "maximise").
- Every tile keeps aspect ratio (`RESIZE_MODE_FIT`), letterboxed in black.
- Slots not shown in the current layout are **stopped and their player released** (frees the account's
  single connection and a decoder).

### 2.3 Slot = account (fixed binding)
Slot N always plays from Account N. There is no account choice per tile. If Account N is empty, the
tile shows `Akun belum diisi`.

### 2.4 Remote controls

| Context | Key | Action |
|---|---|---|
| Main | D-pad | Move highlight between visible tiles (spatial, no wrap). **Audio follows the highlight immediately** |
| Main | OK | Open channel picker for the highlighted slot |
| Main | Long-press OK | Open menu |
| Main | Back | Toast "Tekan Kembali sekali lagi untuk keluar"; second Back within 2 s exits (players released) |
| Picker | D-pad / OK | Navigate categories ↔ channels; OK on a channel plays it in that slot and closes the picker |
| Picker | Back | Close picker without change |
| Menu | OK | Layout 1 / 2 / 3 / 4 · Akun · Muat ulang daftar channel · Keluar |

### 2.5 Channel picker (overlay on the right 40 % of the screen; video keeps playing behind)
- Header: account label (owner-chosen, e.g. "Akun 2").
- Top row: search field (on-screen keyboard; the Google TV phone app keyboard also works).
- Left column: categories from `get_live_categories`, first item `Semua`, second item `Terakhir`
  (last 20 channels played in this slot).
- Right column: channels of the selected category, text only (name). Current channel marked with `●`.
- Search: case-insensitive, accent- and whitespace-normalised substring over channel names, across all
  categories of that account. Results update after 250 ms debounce.
- Must stay smooth with 30 000 channels per account (RecyclerView, in-memory filtering off the main
  thread).

### 2.6 Accounts screen (from menu)
- Four fixed rows: Akun 1–4. Each: label, server URL (`http(s)://host:port`), username, password,
  output format (`Otomatis | TS | HLS`).
- `Tes` button calls auth (§4.1) and shows: status, expiry date (WIB), `max_connections`,
  `active_cons`, allowed formats. Never shows the password after save (masked field, "ubah" to replace).
- `Hapus` clears the account and stops its slot.
- `FLAG_SECURE` on this screen.

## 3. Architecture

Single app module, single process, no background services, no network when the activity isn't visible.

```
app/src/main/java/id/my/doncorleonez/multiview/
  MainActivity.kt          # owns layout, focus, the 4 slot controllers, picker + menu overlays
  layout/GridLayout.kt     # computes tile rects for layouts 1–4 from the root view size
  slot/SlotController.kt   # one per slot: ExoPlayer lifecycle, retry, state text
  slot/AudioFocusPolicy.kt # exactly one slot audible
  picker/PickerView.kt     # categories, channels, search
  menu/MenuView.kt
  accounts/AccountsActivity.kt
  accounts/AccountStore.kt # Keystore-wrapped secrets + non-secret prefs
  xtream/XtreamClient.kt   # HttpURLConnection + org.json; auth, categories, streams
  xtream/ChannelCache.kt   # per-account JSON cache on disk
  xtream/StreamUrl.kt      # builds playback URLs; the only place credentials enter a URL
  util/Redact.kt           # strips credentials from any string before log/UI
```

Stack (pin exact versions in `gradle/libs.versions.toml` at M1, latest stable at that time):

| Concern | Choice |
|---|---|
| Language / UI | Kotlin, plain Android Views. No Compose |
| SDK | `minSdk 29`, `targetSdk 35`, `compileSdk 35`, JDK 17 |
| Player | AndroidX Media3: `media3-exoplayer`, `media3-exoplayer-hls`, `media3-ui` (latest stable 1.x) |
| HTTP for API | `HttpURLConnection` (Media3's default data source for streams) |
| JSON | `org.json` (framework) |
| Async | `kotlinx-coroutines-android` |
| Lists | `androidx.recyclerview` |
| Tests | JUnit 4, Robolectric, a local mock Xtream server (JDK `HttpServer`) with checked-in fixtures |

Forbidden without an issue first: Compose, Room, WorkManager, Retrofit/OkHttp, image libraries,
Firebase/analytics/crash SDKs, WebView, any ad SDK.

Manifest:
- `INTERNET`. Nothing else beyond what Media3 needs.
- `android:usesCleartextTraffic="true"` via a network-security-config (most Xtream servers are http).
- `android:allowBackup="false"`, `android:fullBackupContent="false"`.
- Main activity: `LEANBACK_LAUNCHER` + `LAUNCHER` intent filters, `android:banner` (320×180 plain text
  banner "Multiview"), `screenOrientation="landscape"`, `configChanges` covering keyboard/screen to
  avoid restarts.
- `uses-feature android.software.leanback required=true`, `android.hardware.touchscreen required=false`.

## 4. Xtream API

### 4.1 Calls
- Auth: `GET {base}/player_api.php?username=U&password=P` → `user_info.status`, `exp_date`,
  `max_connections`, `active_cons`, `allowed_output_formats`; `server_info.timezone`.
- Categories: `…&action=get_live_categories`
- Streams: `…&action=get_live_streams` (all at once; one request) → `stream_id`, `name`,
  `category_id`, `num`.
- Nothing else. **No EPG, no VOD, no series.**

### 4.2 Playback URL
- TS: `{base}/live/U/P/{stream_id}.ts`
- HLS: `{base}/live/U/P/{stream_id}.m3u8`
- `Otomatis`: TS if `allowed_output_formats` contains `ts`, else HLS. If playback of the chosen format
  fails with a source/parse error (not a 4xx), try the other format once and remember it per account.
- Set a plain User-Agent (`Multiview/<version>`); allow cross-protocol redirects (many providers 302 to a
  load-balancer host).

### 4.3 Channel cache
- Per account, store categories + streams as JSON in `filesDir/cache/acc{N}.json` with a timestamp.
- Load from cache instantly on start; refresh in the background if older than 24 h or on
  "Muat ulang daftar channel". Failure keeps the old cache.
- Last channel per slot is stored as `stream_id` + name; if the id vanishes after refresh, show
  `Pilih channel`.

## 5. Security

- Passwords encrypted with an AES-GCM key in Android Keystore (hardware-backed on this device). If the
  Keystore call fails, **do not save**; show an error.
- Credentials appear only inside `StreamUrl` and `XtreamClient`. Every log line and every error message
  shown on screen passes through `Redact` (replaces `/live/U/P/` path segments and `username=`/
  `password=` query values). Media3 exceptions carry the URI — wrap them.
- Release builds: no verbose logging. No crash reporting. No telemetry.
- CI test: run the full mock-server test suite with a known password and assert it never appears in
  captured logs or in any `TextView` text.

## 6. Playback behaviour (the important part)

### 6.1 Players
- One `ExoPlayer` per **visible** slot, each on its own `SurfaceView`. Create on show, `release()` on
  hide/stop/activity stop. Max 4.
- `onStop()` releases all players (frees provider connections). `onStart()` restores layout, highlight
  and last channels, and starts them.

### 6.2 Quality = what the channel sends
- No transcoding, no resolution cap.
- HLS: `trackSelectionParameters` with `setForceHighestSupportedBitrate(true)`; no viewport-size
  constraint (`clearViewportSizeConstraints()`), so a small tile still gets the top variant.
- TS: single rendition, played as-is.

### 6.3 Audio follows highlight
- Highlighted slot: audio track type enabled, volume 1.
- All other slots: `setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)` — saves decode work and avoids
  several players fighting over HDMI passthrough (AC-3/E-AC-3). Only one audio output ever exists.
- Switching highlight: disable on old, enable on new in the same frame. A short audio gap on the new
  tile (re-selection) is acceptable; video must not stop.

### 6.4 Decoder limits
- If a player fails with a `MediaCodec` init/decoder error, that tile shows `Decoder penuh`, its player
  is released, other tiles are untouched. OK on that tile retries once.
- Log (redacted) `onDroppedVideoFrames` and `onVideoCodecInitialized` per tile for the M4 measurements.
- Frame-rate switching **off** (`setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)`)
  so changing highlight never triggers an HDMI mode change. In Layout 1 it may stay off too (simplicity).

### 6.5 One connection per account — never two
- Changing channel in a slot: **fully release the old player, then** create the new one. Never overlap.
- HTTP 403/401/458/509 or similar on stream open → show `Koneksi ditolak provider`, retry after 3 s,
  6 s, 12 s, then stop and wait for OK (connections often take a few seconds to free on the provider).

### 6.6 Recovery
- Network/source error or stall > 8 s while playing: `Menyambung ulang…`, re-prepare with backoff
  1, 2, 4, 8, 15, 15… s while the tile is visible. Reset backoff after 60 s of clean playback.
- HLS `BehindLiveWindowException`: seek to default position and re-prepare immediately.

### 6.7 Persistence (SharedPreferences, non-secret)
- Current layout, highlighted slot, per slot last `stream_id`/name, per slot last-20 history,
  per account resolved format.

## 7. Acceptance tests

Automated (CI, mock Xtream server):
- A-01 Auth parses status, expiry, `max_connections`, formats; bad password → clear error, nothing saved.
- A-02 Categories/streams parse; 30 000-channel fixture loads and filters in < 300 ms on CI JVM.
- A-03 Search normalisation (case, accents, extra spaces).
- A-04 `StreamUrl` builds TS/HLS URLs; `Redact` removes credentials from URLs, query strings and
  Media3-style exception messages.
- A-05 Password never in logs or view text during the whole suite.
- A-06 Layout rects for 1–4 at 1920×1080 match §2.2; focus graph: every D-pad move lands on the defined
  neighbour, no wrap.
- A-07 Audio policy: in every layout and after every highlight move, exactly one slot has audio enabled.
- A-08 Channel change in a slot: old player released before new one is created (mock server sees at most
  1 open connection per account at any time).
- A-09 Provider 403 → backoff sequence 3/6/12 s then stops; network drop → reconnect loop.
- A-10 Hide slot (layout 4→2) releases S3/S4; `onStop` releases all; `onStart` restores state.
- A-11 Keystore failure (fault-injected) → password not persisted.
- A-12 Manifest: only `INTERNET` permission; `allowBackup=false`; leanback launcher + banner present.

On device (M4, Claude Code + owner, real remote):
- D-01 4 accounts entered; each `Tes` shows correct `max_connections`.
- D-02 Layout 4 with four 1080p H.264 channels, 30 min: record dropped frames per tile, decoder errors,
  audio switch latency. Repeat with HEVC channels and with any 2160p channel.
- D-03 Highlight moves: audio switches within 500 ms; no HDMI blank/mode change.
- D-04 Change channel 20× quickly on one slot: no `Koneksi ditolak` caused by our own overlap.
- D-05 Pull network 30 s, restore: all tiles recover without user action.
- D-06 Home → back into app: same layout and channels resume.
- D-07 Exit app: provider shows `active_cons` back to 0 within its normal timeout.

Results go into `docs/DEVICE_RESULTS.md`. If D-02 fails at 4 tiles, the outcome is documented
(e.g. "4× H.264 OK, 4× HEVC not"); the app already degrades per tile, no code change needed.

## 8. Milestones (one PR each; stop after each for review)

| M | Deliverable | Done when |
|---|---|---|
| M1 | Repo scaffold, CI (build, unit + Robolectric, lint, manifest check, APK ≤ 6 MB), `AccountStore`, `XtreamClient`, `ChannelCache`, `Redact`, Accounts screen, mock server | A-01–A-05, A-11, A-12 pass |
| M2 | Single slot: `SlotController`, Layout 1, picker with categories/search/history, persistence | A-08, A-09 pass; manual run on emulator against mock server |
| M3 | Layouts 2/3/4, focus graph, audio policy, menu, release-on-hide | A-06, A-07, A-10 pass |
| M4 | Device session (Claude Code + owner): install via adb, D-01–D-07, `DEVICE_RESULTS.md` | results recorded; any fixes as follow-up PRs |

## 9. Repo

- Repo: `doncorleonez/multiview` (new; name changeable). Branch `main` protected; feature branches
  `m<N>-<topic>` → PR.
- Package / applicationId: `id.my.doncorleonez.multiview`.
- Licence: MIT. Commit style `area: change` (areas `app`, `xtream`, `player`, `ui`, `ci`, `docs`).
- Release signing key created by the owner locally; never in the repo. CI builds debug + unsigned release.
- `.gitignore`: `local.properties`, `*.jks`, `*.keystore`, `shots/`, `work/`, `*.apk`.

## 10. Install on the Streamer (reference for M4, run by Claude Code with the owner)

1. Settings › System › About › tap *Android TV OS build* 7× → Developer options.
2. Developer options › Wireless debugging on › *Pair device with pairing code*.
3. Termux: `adb pair <ip>:<pair-port> <code>` then `adb connect <ip>:<port>`.
4. `adb install -r multiview-release.apk`. The app appears in Google TV › Apps.
5. Turn Wireless debugging off after the session.

---

## 11. Implementation notes (as built, 2026-10-24)

Built in one pass by Claude Code. Where the code differs from the text above, the code wins and the
reason is recorded here.

| Spec | As built | Why |
|---|---|---|
| RecyclerView for lists | Framework `ListView` | Zero extra dependency; with recycled rows it scrolls 30 000 items fine on TV. Only Media3 is a dependency |
| `kotlinx-coroutines` | Single-thread `Executor` + main `Handler` | Two background jobs in total (channel lists, picker filter); no dependency needed |
| `org.json` | `core/JsonReaderLite` (streaming) | `get_live_streams` can be tens of MB; streaming keeps memory flat and runs in plain JVM tests |
| Robolectric | Pure-JVM tests of `core/` | All logic (engine, wall, focus, audio policy, parsing, redaction, stores) lives in `core/` with no Android imports; Android classes are thin adapters |
| Cache `acc{N}.json` | `filesDir/cache/acc{N}-{hash}.bin` (binary) | Faster load; hash of server+user so a changed account never shows a stale list |
| OK on a `Decoder penuh` / rejected tile retries | OK opens the picker as always; picking any channel (including the same one) retries | One rule for OK; no hidden mode |
| A-05 "no password in view text" | Every on-screen error string is a fixed text or passes `Redact`; tests assert logs and exception messages | No Robolectric view tree in CI |
| A-12 manifest check | `tools/check-apk.sh` on the built release APK (aapt2): permissions, allowBackup, leanback launcher, banner, size ≤ 6 MB | Checks the merged manifest, i.e. what is actually installed |

Code map:

```
core/        pure Kotlin, unit-tested
  SlotEngine   per-tile state machine: release-before-start, backoff, format fallback, stall/connect timeouts
  Wall         layout + highlight + visibility + single audible slot
  Grid         tile rects and D-pad graph
  XtreamClient auth / categories / streams (HttpURLConnection + JsonReaderLite)
  ChannelList  column-wise channel store, search, binary cache
  Stores       AccountStore (Keystore-sealed password), ViewState
  Redact, StreamUrl, Backoff, Models
platform/    Android adapters: SharedPreferences, AndroidKeyStore AES-GCM, logcat, Handler scheduler, ChannelRepo
player/      Media3Port (ExoPlayer per visible tile) + error classification
ui/          TileView, PickerView, MenuView
MainActivity, AccountsActivity
```

Versions: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1, Media3 1.6.1, compile/target SDK 35, min SDK 29.

Signing: CI signs the release APK with the key in secrets `MV_KEYSTORE_B64` + `MV_STORE_PASSWORD`
(alias `multiview`, key password = store password). Without them it falls back to a throwaway debug
key, and each new build then needs an uninstall (which wipes the saved accounts).
