# 更新日志

## v0.3.2 - 远程 Embedding 与本地向量检索 (2026-08-03)

本版本在 no-node Android 架构中恢复 SillyTavern 官方 Vector Storage / RAG 能力，通过用户配置的远程 Embedding Provider 生成向量，并使用应用私有 SQLite 保存可重建索引。

### 新增能力

- 支持 OpenAI 和 Custom OpenAI-compatible Embedding Provider，可保存配置并测试连接维度。
- 支持 Data Bank、聊天记忆和 World Info 向量激活，兼容七个 `/api/vector/*` 接口。
- 使用 Android framework SQLite 保存归一化 Float32 向量，提供精确 Top-K 查询、模型/端点 namespace 隔离和进程重启持久化。
- Vector Storage 各类 RAG 开关默认关闭；首次启用前明确提示文本将发送给远程 Provider，并可能产生 API 费用。

### 稳定性与隐私

- Provider 限流、超时、错误响应、维度变化、数据库损坏和存储空间不足均返回稳定错误码，批量写入保持原子性。
- 向量日志、Android logcat、诊断导出和 HTTP 错误响应不记录 API key、Authorization、Base URL、用户文本、metadata 正文或向量内容。
- APK 不包含 Node.js、本地 Embedding 模型、ONNX、FAISS、HNSW 或 native vector extension。
- 已在 Pixel 8 / Android 15（API 35）完成远程 Embedding、Vector query、真实 RAG、强停重启持久化和日志隐私验收；项目正式维护范围为 API 35 及以上。

## v0.3.1 - 扩展下载稳定性修复 (2026-07-27)

本版本修复真机在较慢网络下安装大型 GitHub 扩展时，下载超过 OkHttp 默认 10 秒读取超时后失败的问题。

### 修复内容

- 为扩展 GitHub 下载链路设置独立且有上限的超时：连接 20 秒、读取 120 秒、写入 20 秒、单次调用 180 秒。
- 对 metadata、commit、archive redirect、archive download 和 archive read 阶段进行分类诊断，便于区分失败位置。
- 扩展源失败日志仅记录操作、阶段和异常类型，不记录仓库 URL、响应正文、压缩包内容或异常消息。
- 严格校验 GitHub 返回的 `default_branch` 与 commit `sha` 字段，统一包装读取中断和无效响应。
- 保持现有扩展事务、回滚机制和 `502 extension_source_unavailable` API 合同不变。

## v0.3.0 - 无 Node 原生正式版 (2026-07-25)

stAPK 0.3.0 正式版将 SillyTavern 官方 Web UI 与 Android 原生兼容后端整合为不含 Node.js 运行时的单用户 APK，并完成移动端导入、流式传输与扩展事务恢复的正式验收。

### 核心能力

- 通过 Kotlin 原生适配层提供 OpenAI-compatible 模型列表、聊天补全、角色卡、聊天、World Info、预设、Regex、Summarize、媒体与 SAF 导入导出。
- 支持 OpenAI-compatible 流式与非流式回复；Streaming 开关随预设保存和导入，默认关闭。
- 适配 Android 系统文件选择器及部分 OEM 文件管理器返回的文件参数，修复角色卡导入无响应。
- 修复 World Info 导入后被误认为不可取消的全局世界书，并补齐移动端全局世界书选择与清空交互。

### 扩展稳定性

- 扩展安装、更新和删除改为带 journal 的事务流程，并使用应用级共享锁阻止并发 mutation 破坏状态。
- 启动时自动恢复安装、更新或删除中断留下的事务；无法安全采用的目录进入 quarantine，不再由空目录永久阻塞重新安装。
- 对 archive、路径、sidecar 和 registry 执行严格校验，失败时回滚并返回明确错误。
- 修复扩展删除失败仍显示成功并刷新页面的问题，补充移动端可见操作的 UI capability 合同与构建期校验。

### 发布与兼容性

- APK 运行时不包含 Node.js、npm、`node_modules`、`server.js`、Termux 或 Shell 服务进程。
- 已在 Pixel 8 / Android 15（API 35）完成预设持久化、扩展完整生命周期、事务恢复、诊断脱敏和无运行时 Node 验收。
- 第三方 client-only 扩展仅在其依赖的 SillyTavern API 已由原生适配层实现时可用。
- 0.3.0 建议全新安装；不承诺从 0.2.x 自动迁移数据，替换旧版本前请先导出需要保留的内容。

## v0.3.0-beta.1 - 无 Node 原生适配测试版 (2026-07-18)

这是 stAPK 0.3.0 的首个公开测试版本。应用不再在 Android 设备中携带或启动 Node.js，而是在构建期把 SillyTavern Web 资源转换为由 Kotlin 原生适配层承载的 APK。

### 核心变化

- 新增一键 no-node 转换与 APK 构建链路，固定执行转换测试、能力合同校验、Android 单测和 Release 构建。
- 原生实现 OpenAI-compatible 主 API、聊天、角色卡、World Info、预设、Regex、Summarize 和 SAF 数据导入导出桥。
- 支持第三方扩展安装、启用、禁用、版本检查、删除和重新安装，并保留第三方代码风险提示。
- 修复中文 World Info 与 Regex 内容乱码、World Info 删除、角色卡内嵌 World Info 自动导入和条目正文显示。
- APK 内置转换后的 SillyTavern Web 资源，启动后不需要解压或拉起 Node.js 服务。

### 测试版说明

- 这是 beta 版本，建议先备份重要数据；旧版本数据自动迁移仍作为正式版完成后的可选开发项。
- 第三方扩展兼容性取决于其使用的 SillyTavern API，依赖未实现服务端能力的扩展可能无法完整运行。
- 正式发布资产为不含 native runtime 的通用 Android APK。

## v0.2.0 - Android 原生外壳重构版 (2026-06-25)

这是 stAPK Mobile 的一次重大里程碑重构！我们彻底抛弃了原本依赖的 Termux 底层，重新编写了纯原生的 Android 客户端外壳，使得应用更加轻量、专一且符合直觉。

### 🚀 全新特性

- **全新原生体验**：告别终端控制台，启动直接挂载服务，用户界面清爽简单。
- **内置私有环境**：完全脱离 Termux 依赖，使用内置的私有 Node.js 与 Payload，避免外部环境污染与权限问题。
- **原生控制面板**：提供了一键启动服务、查看日志等操作，摒弃了复杂的命令行输入。
- **后台保活服务**：支持点击 "Open Browser" 拉起 Chrome 等外部浏览器使用，自动开启前台服务（Foreground Service）并持有唤醒锁（WakeLock），有效防止因切后台导致的断连或进程被杀。
- **一键备份/恢复**：引入了原生的 Android 存储访问框架 (SAF)，支持将数据目录一键导出为 `.zip` 压缩包，或者从外部导入恢复数据。

### 📖 简易使用指南

1. **初次启动**：安装并打开 App，如果是第一次运行，后台会自动解压基础环境和组件。您可以直接在界面的日志区域（Log）观察进度，确认看到类似 Payload Ready 的提示即代表解压完成。
2. **正常使用**：点击 **"Start Server"** 启动服务。稍等片刻后，您可以在下方内置的 WebView 直接浏览酒馆界面（点击 WebView 区域即可进入沉浸式全屏，按手机返回键即可退出全屏回到控制面板）；或者点击 **"Open Browser"** 在手机自带的原生浏览器中打开。使用原生浏览器时，通知栏会显示驻留通知为您“保活”，防止服务被杀。
3. **数据备份**：点击 **"Backup Data"** 按钮，会弹出一个系统的文件保存窗口。选择一个您记得住的文件夹点击保存，您的**所有核心数据（包括：角色卡片、聊天记录、世界书、用户设置、插件数据等）**就会被完整打包成 `.zip` 永久存放在您的手机里。
4. **数据恢复**：如果您换了手机或者重新安装了 App，点击 **"Restore Data"**，选中之前备份的那个 `.zip` 压缩包，稍等几秒钟，所有数据即可原样恢复！

---

## v0.1.2 - 自动发版修复版 (2026-06-08)

### 📦 自动发版修复

- **修复 GitHub Actions 中的 `gradlew` 执行权限问题** — 避免 Linux runner 上出现 `Permission denied`
- **将自定义 bootstrap 纳入 Git LFS** — 让 CI / Release 能拿到正确的 Termux 运行时压缩包
- **将 SillyTavern payload 纳入 Git LFS** — 确保正式 release 包包含 `SillyTavern.tar.gz` 和 `payload-manifest.json`
- **修复 CI Debug 签名文件缺失** — 在工作流中临时生成 `dev_keystore.jks`
- **降低 CI Debug 构建内存峰值** — 改为上传 `universal` debug APK，避免 `packageDebug` 阶段 OOM
- **收紧 Release 签名配置** — 缺失 GitHub Secrets 时直接给出明确失败信息

---

## v0.1.1 - 运行时托管与自动发版版 (2026-06-08)

### 🚀 运行时与启动链路

- **切换为托管运行时** — SillyTavern 启动链路改为由 `TermuxService` 托管 `stapk-runtime`，不再依赖旧的脱管 `nohup` 方案
- **状态模型升级** — 控制面板改为解析结构化状态快照，统一识别 `not_initialized / starting / running / stopped`
- **停止链路重构** — `stapk-stop` 优先清理托管运行时 PID，再兜底处理遗留 `node` 进程

### 🛠 稳定性修复

- **修复 CRLF 脚本问题** — 统一 `assets/stapk/` 为 `LF` 换行，解决 `pipefail`、`$'\\r'`、`unexpected end of file` 等启动失败
- **修复 bootstrap Shebang 遗留问题** — 自动修正 `npm`/`npx` 等脚本中的旧包名路径，避免启动时找不到正确解释器
- **补齐后台任务运行环境** — 修复 `LD_LIBRARY_PATH`、`PATH` 等环境变量传递，避免托管任务拉起后找不到基础运行时
- **脚本与 Intent 合同测试补齐** — 为状态解析、启动 Intent、Shell 环境和脚本格式增加单测覆盖

## [Unreleased]
### Added
- 支持 Node.js 24.17.0 (LTS) 运行时，替换旧版 Node 18 POC 运行时。
- 新增跨平台 Python 脚本 `scripts/build-runtime-archive.sh`（内部调用 Python）以提取 Node 运行时并生成清单。

### 📦 发布流程

- **接入 GitHub Actions CI** — `master` 上的 `push / pull_request` 会自动执行关键单测和 Debug 构建
- **接入 tag 自动发版** — 推送 `v*` 标签后，GitHub 可自动构建 `arm64-v8a release APK` 并上传到 GitHub Release
- **版本显示自动化** — 控制面板底部 `stAPK v...` 改为直接读取当前构建版本，和 tag / APK 元数据保持一致

### ⚠️ 已知问题

- **后台保活仍需继续优化** — 当前托管运行时已切换完成，但“点击启动后需切后台/切回浏览器才有响应”的现象仍在继续排查
- **x86_64 包仅用于调试** — 当前正式自动发版仅面向 `arm64-v8a`，模拟器用 `x86_64` bootstrap 仍不作为正式支持目标

---

> stAPK Termux 基于 [Termux App](https://github.com/termux/termux-app) v0.118.3 构建，采用 AGPL-3.0 许可证
