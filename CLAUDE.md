# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

stAPK Mobile packages [SillyTavern](https://github.com/SillyTavern/SillyTavern) (an AI roleplay web frontend) plus a private Node.js runtime into a single Android APK, so users run it without a command line. The app extracts the runtime + SillyTavern payload into app-private storage, launches `node server.js` on `localhost:8000` via `ProcessBuilder`, and loads it in a WebView.

## Active codebase vs. legacy

- **`mobile/`** — the current, active Android app (package `com.stapk.mobile`, Kotlin). **All current work happens here.**
- **`upstream/termux-app/`** — the legacy v0.1.x approach: a fork of Termux v0.118.3 (package `com.stapk.termux`) that ran SillyTavern via `stapk-*` shell scripts. **Abandoned in the v0.2.0 native rewrite.** `AGENTS.md` documents this old approach and is largely stale — trust `mobile/` source and `docs/superpowers/specs/2026-06-25-stapk-transformer-design.md` over AGENTS.md.

When asked to change app behavior, work in `mobile/` unless explicitly told otherwise.

## Build & test (the `mobile/` app)

```bash
cd mobile

# Pull LFS assets first — payload.tgz and runtime-android-arm64-node24.zip are Git LFS tracked
git lfs pull

./gradlew :app:assembleDebug          # Debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # Release APK (needs signing env vars, see below)
./gradlew :app:testDebugUnitTest      # Unit tests (CI runs this; no test sources exist yet)
```

Windows: use `.\gradlew.bat`. CI uses `--no-daemon`.

`local.properties` (git-ignored) must contain `sdk.dir=<android-sdk-path>`. Release signing reads these env vars: `TERMUX_RELEASE_STORE_FILE`, `TERMUX_RELEASE_STORE_PASSWORD`, `TERMUX_RELEASE_KEY_ALIAS`, `TERMUX_RELEASE_KEY_PASSWORD`.

Build config: `compileSdk=34`, `minSdk=24`, **`targetSdk=28`** (intentionally low — keeps cleartext-to-localhost and process-spawning behavior working; release lint is disabled in `app/build.gradle.kts` to bypass `ExpiredTargetSdkVersion`). ABI is `arm64-v8a` only.

## Runtime architecture (`mobile/app/src/main/java/com/stapk/mobile/`)

Four Kotlin files, no DI/ViewModel framework — plain `Activity` + a `RuntimeManager` orchestrator on a single-thread `Executor`.

- **`RuntimeManager.kt`** — owns the runtime lifecycle in the current 0.2.x-style app. `extractRuntimeIfNeeded()` unzips `assets/runtime-android-arm64-node24.zip` (Node binary + `.so` libs) into `filesDir/runtime/`; `deployPayloadIfNeeded()` shells out to `tar -xzf` on `assets/payload.tgz` to unpack `filesDir/SillyTavern/`. `startSillyTavern()` spawns `node server.js` with `HOME`/`LD_LIBRARY_PATH`/`PATH`/`TMPDIR` set so Node finds its libs and modules. Also holds backup/restore (zip of `config.yaml` + `data/` + third-party extensions). Extraction is guarded by `.flag` files; runtime unzip has a zip-slip canonical-path check.
- **`MainActivity.kt`** — single screen: control buttons + a `TextView` log + a WebView. On create it kicks off runtime/payload extraction in the background, then `pollServer()` HTTP-polls `127.0.0.1:8000` until ready before `webView.loadUrl`. Hosts `BlobDownloader` (a `@JavascriptInterface` that writes WebView blob downloads to public Downloads) and the SAF file-chooser plumbing.
- **`KeepAliveService.kt`** — foreground service holding a `PARTIAL_WAKE_LOCK`. Started when the user taps "Open Browser" (so the Node process survives backgrounding), stopped in `MainActivity.onResume()`. It does **not** own the Node process — `MainActivity` does, and `onDestroy()` kills it.
- **`TavernWebViewClient.kt`** — injects JS on page load to intercept `blob:` download links and route them through `AndroidDownloader`.

Assets in `mobile/app/src/main/assets/`: `payload.tgz` (SillyTavern source + node_modules), `runtime-android-arm64-node24.zip` (Node 24 runtime + libs), `payload-manifest.json` (records the SillyTavern commit/version/runtime metadata), `dummy-server.js`.

## Payload generation (legacy scripts in `scripts/`)

`scripts/prepare-sillytavern-payload.sh` clones SillyTavern `release`, runs `npm install --omit=dev`, scans for native addons, and produces `SillyTavern.tar.gz` + manifest. These scripts predate `mobile/` and target the old `payload/` layout. The 0.3.0 branch now has `scripts/stapk-transform.mjs`, `transform/config/config.android.yaml`, and an empty `patches/sillytavern/series` queue for repeatable payload generation; `scripts/stapk-verify-transform.mjs` and manifest schema validation are still pending.

## Known constraints / gotchas

- The bundled runtime archive is `runtime-android-arm64-node24.zip`, whose manifest records Node `v24.17.0`. The previous Node 18 POC runtime has been removed from the current 0.3.0 worktree.
- `network_security_config.xml` permits cleartext only to `127.0.0.1`/`localhost`; global cleartext stays off. Don't open global cleartext to simplify WebView loading.
- Shell scripts use `set -euo pipefail` and must be LF-only (`.gitattributes` enforces `eol=lf` on `stapk/*`). CRLF here previously broke startup.

## Direction: the 0.3.0 transformer redesign

`docs/superpowers/specs/2026-06-25-stapk-transformer-design.md` is the authoritative spec for the next major version. It reframes the repo from "an app that ships a prebuilt payload" into "a repeatable pipeline that converts any upstream SillyTavern ref into an APK." Key target changes: a transform/verify script pair, a patch queue, reproducible manifests, removing the control panel so the app opens straight into SillyTavern, app-private `user_data` with migration from 0.2.0's `filesDir/SillyTavern/data`, and moving the Node process lifecycle out of `MainActivity` into a service. The spec carefully separates "verified current facts" from "design goals not yet implemented" — preserve that distinction when editing it.

## Conventions

- Commits: Conventional Commits (`feat:`, `fix:`, `docs:`, `build:`, `ci:`...). Commit messages and `CHANGELOG.md` are written in Chinese; release notes are generated from CHANGELOG by `release.yml`.
- `CHANGELOG.md` section headers must match the tag exactly (`## v0.3.0 ...`) — the release workflow extracts release notes by tag name.
