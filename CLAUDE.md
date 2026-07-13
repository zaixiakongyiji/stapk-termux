# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

stAPK Mobile 的当前 0.3.0 主线是把 [SillyTavern](https://github.com/SillyTavern/SillyTavern) 的官方 Web UI 转换成一个不包含 Node.js 运行时的 Android 原生应用。APK 运行时只包含 WebView、静态前端资源、Kotlin/Java 本地 HTTP 兼容后端、用户数据和模型设置；Node.js 只允许作为构建期工具存在。

旧的 0.2.x 路线是“APK 内置私有 Node.js + `payload.tgz` + `node server.js` + WebView”。这条路线保留为历史实现和迁移来源，不再作为新功能的默认扩展方向。

## Active codebase vs. legacy

- **`mobile/`** — the current, active Android app (package `com.stapk.mobile`, Kotlin). **All current runtime work happens here.**
- **`upstream/termux-app/`** — the legacy v0.1.x approach: a fork of Termux v0.118.3 (package `com.stapk.termux`) that ran SillyTavern via `stapk-*` shell scripts. **Abandoned in the v0.2.0 native rewrite.** `AGENTS.md` documents this old approach and is largely stale — trust `mobile/` source and `docs/superpowers/specs/2026-06-25-stapk-transformer-design.md` over AGENTS.md.

When asked to change app behavior, work in `mobile/` unless explicitly told otherwise.

## Build & test (the `mobile/` app)

```bash
cd mobile

# Current 0.2.x assets are still LFS tracked while the no-node migration is in progress.
# Do not add new runtime Node/npm/node_modules assets for 0.3.0 work.
git lfs pull

./gradlew :app:assembleDebug          # Debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # Release APK (needs signing env vars, see below)
./gradlew :app:testDebugUnitTest      # Unit tests (CI runs this; no test sources exist yet)
```

Windows: use `.\gradlew.bat`. CI uses `--no-daemon`.

`local.properties` (git-ignored) must contain `sdk.dir=<android-sdk-path>`. Release signing reads these env vars: `TERMUX_RELEASE_STORE_FILE`, `TERMUX_RELEASE_STORE_PASSWORD`, `TERMUX_RELEASE_KEY_ALIAS`, `TERMUX_RELEASE_KEY_PASSWORD`.

Build config: `compileSdk=34`, `minSdk=24`, current `targetSdk=28`. ABI is `arm64-v8a` only. The no-node migration should remove dependence on process-spawning behavior instead of preserving it.

## Runtime architecture status (`mobile/app/src/main/java/com/stapk/mobile/`)

The checked-in Android app still contains the 0.2.x Node runtime container implementation. Treat it as migration source, not as the desired 0.3.0 architecture.

Four Kotlin files, no DI/ViewModel framework — plain `Activity` + a `RuntimeManager` orchestrator on a single-thread `Executor`.

- **`RuntimeManager.kt`** — owns the runtime lifecycle in the current 0.2.x-style app. `extractRuntimeIfNeeded()` unzips `assets/runtime-android-arm64-node24.zip` (Node binary + `.so` libs) into `filesDir/runtime/`; `deployPayloadIfNeeded()` shells out to `tar -xzf` on `assets/payload.tgz` to unpack `filesDir/SillyTavern/`. `startSillyTavern()` spawns `node server.js` with `HOME`/`LD_LIBRARY_PATH`/`PATH`/`TMPDIR` set so Node finds its libs and modules. Also holds backup/restore (zip of `config.yaml` + `data/` + third-party extensions). Extraction is guarded by `.flag` files; runtime unzip has a zip-slip canonical-path check.
- **`MainActivity.kt`** — single screen: control buttons + a `TextView` log + a WebView. On create it kicks off runtime/payload extraction in the background, then `pollServer()` HTTP-polls `127.0.0.1:8000` until ready before `webView.loadUrl`. Hosts `BlobDownloader` (a `@JavascriptInterface` that writes WebView blob downloads to public Downloads) and the SAF file-chooser plumbing.
- **`KeepAliveService.kt`** — foreground service holding a `PARTIAL_WAKE_LOCK`. Started when the user taps "Open Browser" (so the Node process survives backgrounding), stopped in `MainActivity.onResume()`. It does **not** own the Node process — `MainActivity` does, and `onDestroy()` kills it.
- **`TavernWebViewClient.kt`** — injects JS on page load to intercept `blob:` download links and route them through `AndroidDownloader`.

Legacy assets in `mobile/app/src/main/assets/`: `payload.tgz` (SillyTavern source + node_modules), `runtime-android-arm64-node24.zip` (Node 24 runtime + libs), `payload-manifest.json`, `dummy-server.js`. New 0.3.0 work must replace these with no-node web assets and Android-native backend metadata instead of growing the legacy payload.

## Payload generation (legacy scripts in `scripts/`)

`scripts/prepare-sillytavern-payload.sh` and `scripts/stapk-transform.mjs` belong to the older Node payload direction. They remain useful as references for reproducible upstream checkout, patch queue handling, and manifest generation, but the active 0.3.0 route is the no-node transform pipeline defined in `docs/plan/2026-07-09-stapk-no-node-native-adapter-implementation-plan.md`.

## Known constraints / gotchas

- Do not introduce runtime Node.js, npm, `node_modules`, `server.js` process spawning, or runtime archive extraction for the 0.3.0 path.
- The app may still run a loopback HTTP server inside Android, but it must be implemented by Kotlin/Java and serve static Web assets plus native API compatibility endpoints.
- MVP scope is OpenAI-compatible chat completion only. Non-MVP upstream features should be hidden, patched out, or return explicit unsupported responses rather than silently depending on Node server behavior.
- `network_security_config.xml` permits cleartext only to `127.0.0.1`/`localhost`; global cleartext stays off. Don't open global cleartext to simplify WebView loading.
- Shell scripts use `set -euo pipefail` and must be LF-only (`.gitattributes` enforces `eol=lf` on `stapk/*`). CRLF here previously broke startup.

## Direction: no-node native adapter

`docs/superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md` is the authoritative design for the next major version. It supersedes the Node runtime transformer route in `docs/superpowers/specs/2026-06-25-stapk-transformer-design.md`.

The implementation plan is `docs/plan/2026-07-09-stapk-no-node-native-adapter-implementation-plan.md`. Preserve these boundaries while executing it:

- Android APK runtime has no Node.js, npm, `node_modules`, `server.js`, runtime zip, or payload tar extraction.
- Build-time tools may use Node.js to scan and transform upstream SillyTavern Web assets.
- Official SillyTavern Web UI remains the user-facing interface.
- Native Kotlin/Java code provides the minimal local API surface required by the Web UI.
- Fixed compatibility patches are allowed, but they must live outside upstream source as a repeatable patch queue.

## Conventions

- Commits: Conventional Commits (`feat:`, `fix:`, `docs:`, `build:`, `ci:`...). Commit messages and `CHANGELOG.md` are written in Chinese; release notes are generated from CHANGELOG by `release.yml`.
- `CHANGELOG.md` section headers must match the tag exactly (`## v0.3.0 ...`) — the release workflow extracts release notes by tag name.
