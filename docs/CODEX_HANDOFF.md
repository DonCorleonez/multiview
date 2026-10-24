# CODEX_HANDOFF.md — continue Multiview

Paused by Claude Code on 2026-10-24 17:51 WIB at the owner's request. Codex continues from here.
Read in this order: this file → `docs/MULTIVIEW_HANDOFF.md` (spec, §11 = as-built notes) → `README.md`.

Hard limits (unchanged):
- Never connect to, install on, or change the device. Device sessions = owner (+ Claude Code).
- Never commit credentials, server URLs, IPs, ADB keys, keystores (`*.jks`), or APKs.
- No DRM/certification bypass, no root. No new dependencies without an issue (see spec §3 forbidden list).

---

## 1. Status at pause

| Item | State |
|---|---|
| Code for M1–M3 (all app features) | **Done**, on `main` |
| Unit tests (37, pure JVM, `app/src/test`) | Pass locally and in CI |
| `compileDebugKotlin`, `lintDebug`, `assembleDebug`, `assembleRelease` (R8) | **Pass in CI** (run 3) |
| `tools/check-apk.sh` (permissions, manifest, size) | Run 3 failed on a parsing bug in the script itself (not the app). Fixed in `4993b21`; **CI run 4 was still running at pause — check it first** |
| GitHub Release with APKs | Created automatically by CI on push to `main` once the whole job is green. None yet |
| Release signing secrets | **Not set yet** — owner must add them (§4) before the first install |
| M4 device tests (D-01…D-07) | Not started — owner + Claude Code |

Commits: `094c412` app · `1d3c706` CI failure log as commit comment · `32690f3` ServerSocket mock server ·
`4993b21` aapt2 permission parsing.

## 2. First thing to do

1. Look at the CI result for the newest commit on `main`.
2. **If green:** the release `v1.0.<run>` exists with `multiview-1.0.<run>.apk` and `-debug.apk`. Go to §5.
3. **If red:** the workflow posts the log tail as a **commit comment** on the failing commit (step
   "Report failure log as commit comment"). Read it, fix, push. Likely remaining risk is only in
   `tools/check-apk.sh`:
   - permission allow-list: `INTERNET`, `ACCESS_NETWORK_STATE`, `*.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
     If Media3/AndroidX adds another harmless permission, decide deliberately; do not just widen the regex.
   - `aapt2 dump xmltree` grep patterns (`allowBackup…=false`, `LEANBACK_LAUNCHER`, `android:banner`,
     `android.software.leanback`). Print the xmltree once if a grep fails.
   - size gate 6 MB (expected ≈ 2–3 MB after R8).

Do not loosen the checks to get green; fix the cause.

## 3. How the code is laid out (short)

```
app/src/main/java/id/my/doncorleonez/multiview/
  core/        pure Kotlin, no Android imports — all logic + tests live here
    SlotEngine   per-tile state machine: release-before-start (1 connection/account), backoff
                 (rejected 3/6/12 s then stop; network 1,2,4,8,15… s), AUTO TS→HLS fallback once,
                 stall 8 s, connect timeout 20 s, state texts
    Wall         layout 1–4, highlight, visible slots (hidden ones released), exactly one audible slot
    Grid         tile rects + D-pad neighbour graph (no wrap)
    XtreamClient auth / get_live_categories / get_live_streams; JsonReaderLite = streaming parser
    ChannelList  column-wise store, normalised search, binary cache
    Stores       AccountStore (password sealed via SecretBox; seal failure → nothing saved), ViewState
    Redact       strips /live/U/P/, username=/password=, user@ and registered secrets from all logs/errors
  platform/    PrefsKV, KeystoreBox (AES-GCM, AndroidKeyStore), AndroidLogger, MainScheduler, ChannelRepo
  player/      Media3Port: one ExoPlayer per visible tile, audio track disabled + volume 0 on non-highlighted,
               force highest bitrate, frame-rate switching off, error → FailKind classification
  ui/          TileView (PlayerView + state text + border), PickerView, MenuView
  MainActivity (keys: D-pad move, OK picker, long-press OK menu, Back×2 exit), AccountsActivity (FLAG_SECURE)
```

Rule of thumb for changes: put behaviour in `core/` with a unit test; keep Android classes as thin
adapters. Fakes for tests are in `app/src/test/.../Fakes.kt` (`FakePort` throws if two players overlap).
Unit tests run on the Android test classpath — **no `com.sun.*` APIs** (that is why the mock server is a
plain `ServerSocket`).

## 4. Signing (owner action, before first install)

CI signs `release` with secrets `MV_KEYSTORE_B64` (base64 of a PKCS12 keystore) and `MV_STORE_PASSWORD`
(alias `multiview`, key password = store password; overridable with `MV_KEY_ALIAS` / `MV_KEY_PASSWORD`
env). Without them the release falls back to the runner's throwaway debug key and the workflow shows a
warning — such builds can't be updated in place (uninstall wipes the saved accounts).

The owner already received a generated keystore + the two values out of band. After the owner adds the
secrets, re-run the workflow (`workflow_dispatch`) so the published release is properly signed.
Codex: never generate, print or commit a keystore.

## 5. Remaining work (in order)

| # | Task | Owner | Notes |
|---|---|---|---|
| 1 | Get CI fully green (§2) | Codex | |
| 2 | Owner adds signing secrets, re-run workflow | Owner | §4 |
| 3 | Emulator smoke test (Android TV API 34 AVD) against a local mock Xtream serving a looping TS/HLS clip | Codex | Optional but valuable before M4: verifies picker focus, long-press OK, layout switching, audio follows highlight. A small Python/ffmpeg mock can live in `tools/mock-xtream/` (no real provider data) |
| 4 | M4 device session D-01…D-07 (spec §7) | Owner + Claude Code | Use the `-debug` APK for metrics: `adb logcat -s MV/Player` shows decoder, resolution, dropped frames per slot. Results → `docs/DEVICE_RESULTS.md` |
| 5 | Fixes from M4 as small PRs | Codex | |

Process from here: feature branches `m<N>-<topic>` → PR into `main` (main was pushed directly only for
the initial import). Commit style `area: change`.

## 6. Known risks / things to watch in M4

- **Decoder count on MT8696** for 4× 1080p (and HEVC/2160p): unknown. App degrades per tile
  (`Decoder penuh`); record what works.
- **Audio switch** re-enables the audio track on the new tile → short gap expected (< 500 ms target).
  If gaps are long, alternative is to keep audio decoded on all tiles at volume 0 — but that risks
  HDMI passthrough conflicts (AC-3/E-AC-3). Measure before changing.
- **Long-press OK** relies on `onKeyLongPress`; the Google TV remote must deliver repeat events.
  Fallback key already wired: `KEYCODE_MENU` opens the menu if a remote has it.
- **Search field on TV**: IME opens on OK; the Google TV phone app keyboard is the practical input.
- **Provider quirks**: some panels return 302 to another host (redirects enabled), some send `{}` instead
  of `[]` (handled), some need HLS (`Otomatis` falls back once and remembers per account).
- Media3 version is 1.6.1 (pinned in `gradle/libs.versions.toml`); bump only in its own PR.

## 7. Spec deviations already accepted (details in MULTIVIEW_HANDOFF §11)

ListView instead of RecyclerView; Executor/Handler instead of coroutines; streaming JSON instead of
org.json; pure-JVM tests instead of Robolectric; binary cache file; OK always opens the picker (picking
again = retry after `Decoder penuh` / `Koneksi ditolak provider`).
