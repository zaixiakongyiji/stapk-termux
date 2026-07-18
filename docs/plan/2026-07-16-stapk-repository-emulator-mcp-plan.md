# stAPK 仓库级 Android Emulator MCP 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在当前仓库增加固定管理 `Pixel_8` AVD 的 MCP，并在设备验证前自动确保 Emulator 已就绪。

**Architecture:** 生命周期核心与 MCP stdio 适配分离。核心通过注入的进程执行接口调用固定 `adb`/`emulator` 参数，协议层只暴露五项无参数工具；项目级 `.codex/config.toml` 负责注册，不修改全局配置。

**Tech Stack:** Node.js 20、`@modelcontextprotocol/sdk`、Node test runner、Android SDK Emulator、ADB。

## 全局约束

- AVD 名称固定为 `Pixel_8`。
- 只能停止经 `adb emu avd name` 二次确认的目标 Emulator。
- 启动默认显示独立窗口，ready 等待上限 180 秒。
- 不执行 git commit/push；提交由用户手动触发。

---

### Task 1：实现可测试的 Emulator 生命周期核心

**Files:**
- Create: `scripts/mcp/stapk-emulator-core.mjs`
- Test: `test/mcp/stapk-emulator-core.test.mjs`

**Interfaces:**
- Produces: `createEmulatorLifecycle(options)`，返回 `status()`、`start()`、`ensureStarted()`、`stop()`、`restart()`。
- Consumes: 注入的 `run(command,args)`、`spawn(command,args)`、`sleep(ms)` 和 SDK 根目录。

- [x] **Step 1：写失败测试**

覆盖 `Pixel_8` 状态识别、幂等启动、其他 AVD 不被停止、ready 等待和超时错误。

- [x] **Step 2：确认 RED**

Run: `node --test test/mcp/stapk-emulator-core.test.mjs`

Expected: 因 `stapk-emulator-core.mjs` 不存在或接口未实现而失败。

- [x] **Step 3：实现最小核心**

实现固定命令参数、SDK 路径解析、AVD 列举、serial 到 AVD 名称映射和生命周期状态机。

- [x] **Step 4：确认 GREEN**

Run: `node --test test/mcp/stapk-emulator-core.test.mjs`

Expected: 全部通过，且测试确认不会对其他 AVD 执行 `emu kill`。

---

### Task 2：增加 MCP stdio 适配和仓库配置

**Files:**
- Create: `scripts/mcp/stapk-emulator-mcp.mjs`
- Create: `.codex/config.toml`
- Modify: `package.json`
- Modify: `package-lock.json`

**Interfaces:**
- Produces: `stapk_emulator_status`、`stapk_emulator_start`、`stapk_emulator_ensure_started`、`stapk_emulator_stop`、`stapk_emulator_restart`。
- Consumes: Task 1 的 `createEmulatorLifecycle(options)`。

- [x] **Step 1：增加 SDK 依赖和 MCP adapter**

使用 `@modelcontextprotocol/sdk` 的 stdio transport 注册五项无参数工具，结果以 JSON 文本返回。

- [x] **Step 2：增加项目级配置**

`.codex/config.toml` 仅注册仓库内的 Node MCP 入口，tool timeout 设为 240 秒。

- [x] **Step 3：验证协议和配置**

Run: `npm run test:emulator-mcp`

Expected: 核心测试和 MCP 工具列表 smoke test 均通过。

---

### Task 3：真实 AVD 验收并恢复主体计划

**Files:**
- Modify: `docs/plan/2026-07-16-stapk-repository-emulator-mcp-plan.md`
- Modify: `docs/plan/2026-07-12-stapk-single-user-feature-completion-plan.md`

- [x] **Step 1：调用 `stapk_emulator_ensure_started`**

Expected: 返回 AVD `Pixel_8`、状态 `ready` 和实际 `emulator-*` serial。

- [x] **Step 2：使用现有 `mobile-mcp` 发现同一设备**

Expected: `mobile_list_available_devices` 返回上一步 serial。

- [x] **Step 3：继续 Task 10 验证**

Run: `mobile\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest`

Expected: 全量 JVM 测试通过；随后安装最新 APK 并验证诊断 summary/export。

## 2026-07-16 执行证据

- `npm run test:emulator-mcp`：7/7 通过。
- `codex mcp list`：项目配置已识别 `stapk-emulator`，入口为 `scripts/mcp/stapk-emulator-mcp.mjs`。
- 真实调用 `stapk_emulator_ensure_started`：返回 `Pixel_8`、`emulator-5554`、`ready`、`started=true`。
- 真实调用 `stapk_emulator_restart`：安全停止后重新返回 `ready`、`restarted=true`。
- `mobile_list_available_devices`：识别同一 `emulator-5554`，Android 15，状态 online。
- 全局 `C:\Users\31029\.codex\config.toml` 未修改；重新打开 Codex task 后项目 MCP 工具自动加载。
