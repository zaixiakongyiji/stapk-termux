# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

stAPK Mobile 的当前 0.3.0 主线是把 [SillyTavern](https://github.com/SillyTavern/SillyTavern) 的官方 Web UI 转换成一个不包含 Node.js 运行时的 Android 原生应用。APK 运行时只包含 WebView、静态前端资源、Kotlin/Java 本地 HTTP 兼容后端、用户数据和模型设置；Node.js 只允许作为构建期工具存在。

所有当前运行时开发均在 `mobile/` 中进行；不要重新引入旧的 APK 内置 Node.js、payload archive 或 `node server.js` 路线。

## Build & test

```bash
npm ci
npm run test:no-node
npm run build:no-node-apk -- --variant debug --ref release
```

一键构建固定执行 no-node tests、transform、产物 verifier、严格 capability verifier、Android JVM tests 和 Gradle assemble，然后向 `output/` 发布 APK、checksum、API contract、capability runtime、Web manifest 和 transform report。Release 使用 `--variant release` 并需要下述签名环境变量。

截至 2026-07-17，Debug 候选已通过上述单命令、Pixel 8 / Android 15（API 35）clean install、无 Node 进程、官方单用户 UI 能力矩阵和真实外部 OpenAI-compatible provider 验收；稳定冷启动基线第 3 秒显示原生启动页而非黑屏，第 13 秒官方 UI 可用，`app_ready` 约 12.1 秒。API 24/29 延期到后续真机验收。证据和待办以 `docs/plan/2026-07-12-stapk-single-user-feature-validation-record.md` 为准。0.3.0 按全新安装处理，旧数据迁移及完整应用备份恢复不阻断主体发布。

## Android Emulator MCP

本仓库通过 `.codex/config.toml` 注册 `stapk-emulator` MCP，只管理本机 `Pixel_8` AVD。设备验证前先调用 `stapk_emulator_ensure_started`，等待返回 `state=ready` 和实际 `emulator-*` serial，再使用全局 `mobile-mcp` 安装、启动和操作 APK。

- `stapk_emulator_status`：查询目标 AVD 状态。
- `stapk_emulator_start` / `stapk_emulator_ensure_started`：启动可见的独立 Emulator 窗口；重复调用不会创建第二台。
- `stapk_emulator_stop` / `stapk_emulator_restart`：只操作经 AVD 名称确认的 `Pixel_8`，不得影响其他 Emulator 或实体设备。
- 修改 `.codex/config.toml` 后需要重新打开 Codex task，才能加载新增 MCP 工具。

`local.properties` (git-ignored) must contain `sdk.dir=<android-sdk-path>`. Release signing reads these env vars: `TERMUX_RELEASE_STORE_FILE`, `TERMUX_RELEASE_STORE_PASSWORD`, `TERMUX_RELEASE_KEY_ALIAS`, `TERMUX_RELEASE_KEY_PASSWORD`.

Build config: `compileSdk=34`, `minSdk=24`, current `targetSdk=28`. ABI is `arm64-v8a` only. The no-node migration should remove dependence on process-spawning behavior instead of preserving it.

## Runtime architecture status (`mobile/app/src/main/java/com/stapk/mobile/`)

- **`MainActivity.kt`** — 绑定原生 foreground service，加载随机 loopback 端口的官方 Web UI，并协调 SAF 导入导出。
- **`nativeadapter/NativeHttpService.kt`** — 拥有本地 HTTP server 生命周期，不启动任何 Node 进程。
- **`nativeadapter/NativeHttpServer.kt`** — 提供静态 Web、单用户数据、OpenAI-compatible provider、诊断和导出兼容接口。
- **`TavernWebViewClient.kt` / `StapkFileBridge.kt`** — 限定 loopback 主文档、外部 HTTPS 跳转和带 nonce 的 SAF bridge。
- **`mobile/app/src/main/assets/`** — 只包含 no-node Web 资产、API contract、capability runtime、manifest 和 transform report，不得重新加入 runtime archive 或 payload tar。

## Payload generation

当前构建期转换由 `scripts/stapk-transform-no-node.mjs` 和 `scripts/stapk-build-no-node-apk.mjs` 负责。不要重新引入 Node runtime、payload archive 或运行时解压流程。

## Known constraints / gotchas

- Do not introduce runtime Node.js, npm, `node_modules`, `server.js` process spawning, or runtime archive extraction for the 0.3.0 path.
- The app may still run a loopback HTTP server inside Android, but it must be implemented by Kotlin/Java and serve static Web assets plus native API compatibility endpoints.
- Provider scope is OpenAI-compatible chat completion。普通单用户本地功能按 capability contract 开放；远程模型能力显示外部服务要求，本地重型模型、任意 Node 扩展和 multiuser 保持排除。
- `network_security_config.xml` permits cleartext only to `127.0.0.1`/`localhost`; global cleartext stays off. Don't open global cleartext to simplify WebView loading.
- Shell scripts use `set -euo pipefail` and must be LF-only (`.gitattributes` enforces `eol=lf` on `stapk/*`). CRLF here previously broke startup.

## Direction: no-node native adapter

`docs/superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md` is the authoritative design for the next major version. It supersedes the Node runtime transformer route in `docs/superpowers/specs/2026-06-25-stapk-transformer-design.md`.

The active implementation plan is `docs/plan/2026-07-12-stapk-single-user-feature-completion-plan.md`. Preserve these boundaries while executing it:

- Android APK runtime has no Node.js, npm, `node_modules`, `server.js`, runtime zip, or payload tar extraction.
- Build-time tools may use Node.js to scan and transform upstream SillyTavern Web assets.
- Official SillyTavern Web UI remains the user-facing interface.
- Native Kotlin/Java code provides the minimal local API surface required by the Web UI.
- Fixed compatibility patches are allowed, but they must live outside upstream source as a repeatable patch queue.

## Conventions

- Commits: Conventional Commits (`feat:`, `fix:`, `docs:`, `build:`, `ci:`...). Commit messages and `CHANGELOG.md` are written in Chinese; release notes are generated from CHANGELOG by `release.yml`.
- `CHANGELOG.md` section headers must match the tag exactly (`## v0.3.0 ...`) — the release workflow extracts release notes by tag name.
