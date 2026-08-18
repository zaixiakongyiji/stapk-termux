# stAPK 无 Node 原生适配转换器设计

日期：2026-07-09
状态：0.3.x 主体已实施并完成 API 35 验收；v0.3.2 已加入远程 Embedding、本地 SQLite Vector Storage 与 RAG
范围：保留 SillyTavern 官方 Web UI，APK 运行时不包含、不解压、不启动 Node.js，通过 Android 原生兼容层承接官方单用户核心能力；本地重型模型和任意 Node 扩展不属于主体完成范围

## 结论

旧 0.3.0 transformer 计划本质是 Node 容器化路线：

```text
SillyTavern upstream
    -> 生成 payload.tgz
    -> APK 内置 payload.tgz + Node runtime
    -> 首次启动解压 payload 和 runtime
    -> Android 启动 node server.js
    -> WebView 访问 http://127.0.0.1:8000/
```

这条路线已暂停。新的目标不是把 Node runtime 包进 APK，也不是让 Android 只当 `server.js` 的进程管理壳。

新路线是无 Node 原生适配转换器：

```text
SillyTavern upstream
    -> 转换器拉取指定 ref
    -> 应用固定 Android patch queue
    -> 提取官方 Web UI 静态资源
    -> 生成 Android assets、契约清单和 API 兼容报告
    -> APK 内置 Web UI 资源和原生兼容层
    -> Android Kotlin/Java 提供本地 HTTP 兼容后端
    -> WebView 展示官方 SillyTavern UI
```

运行时约束：

```text
APK 内不包含 Node.js runtime
APK 内不包含 node_modules
APK 不执行 node / npm / server.js
APK 可以使用 WebView 执行前端 JavaScript
APK 可以启动 Android 原生本地 HTTP server
APK 可以通过 Kotlin/Java/Android 库访问文件、网络、加密、图片和系统能力
```

本设计不追求通用 Node 项目转换器。它是针对 SillyTavern 的原生兼容实现：转换 upstream Web UI 和资源，少量 patch 平台边界，Android 原生层实现官方单用户核心后端 API。项目主体完成不等于复刻全部 Node 服务端，也不要求在 APK 内重建本地 AI 模型运行平台。

## 名词定义

| 名词 | 含义 |
|------|------|
| upstream | `SillyTavern/SillyTavern` 官方仓库指定 ref |
| 转换器 | 本仓库构建期脚本，负责拉取 upstream、应用 patch、产出 Android 可打包资源和契约报告 |
| patch queue | 本仓库维护的固定 patch 列表，只应用在构建工作目录，不修改 upstream 仓库 |
| 官方 Web UI | upstream `public/` 下的 HTML/CSS/JS/图片/字体/locale 等前端资源 |
| 原生兼容层 | Android Kotlin/Java 实现的本地 HTTP API，模拟 SillyTavern Node 后端对前端暴露的必要接口 |
| 无 Node | APK 运行时没有 Node.js binary、没有 `node_modules`，也不通过 ProcessBuilder/JNI 启动 Node |
| OpenAI-compatible | 第一版真实对话只支持 OpenAI Chat Completions 兼容协议，包括 OpenAI 官方、OpenRouter、DeepSeek 或用户自定义 base URL |

## 当前事实

当前仓库已经完成无 Node 原生适配主体，并按 capability contract 区分已实现、远程可选和明确隐藏的能力：

- `mobile/` 是当前 Android 工程，包名 `com.stapk.mobile`。
- `mobile/app/src/main/assets/` 已切换为 `sillytavern-web/`、API 契约、Web manifest 和 transform report，不再包含 `payload.tgz` 或 Node runtime ZIP。
- 旧 `RuntimeManager.kt` 和 `KeepAliveService.kt` 已删除，manifest 只注册新的 `NativeHttpService`。
- `MainActivity.kt` 已切换为 loading/WebView/error 三态壳，不再展示控制面板，也不再启动 Node。
- 旧 Node runtime + WebView 容器路线及其收口计划只保留在 Git 历史中，不再作为当前开发依据。

当前 payload 中核对到的 SillyTavern 结构：

- `server.js` 是 Node 后端入口。
- `src/server-main.js` 注册中间件、静态资源和 API 路由。
- `public/` 包含官方 Web UI 静态资源。
- `src/endpoints/` 包含大量后端 API：characters、chats、settings、secrets、openai、backends、extensions、worldinfo、tokenizers、images、tts、vectors 等。
- `public/script.js` 和相关前端脚本通过 `fetch('/api/...')` 调用后端。
- OpenAI-compatible 真实生成路径主要涉及 `/api/backends/chat-completions/generate`、`/api/backends/chat-completions/status`、settings、secrets 和模型配置。

## 实施进度（更新至 2026-08-18）

- `docs/plan/2026-07-12-stapk-single-user-feature-completion-plan.md` 已完成主体任务并保留为历史实施记录；新功能必须单独设计和计划。
- 已落地构建期 API 契约扫描器：`scripts/stapk-scan-web-contract.mjs`。
- 已落地 no-node transform 和 verifier：`scripts/stapk-transform-no-node.mjs`、`scripts/stapk-verify-no-node-transform.mjs`。转换器会执行 upstream Webpack 构建并把 `public/lib.js` 替换为浏览器 bundle，verifier 会阻断裸模块 import。
- 真实 transform 已从 SillyTavern `release` 生成 `build/no-node-payload/`，resolved commit 为 `8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8`。
- 当前输出包含 `sillytavern-web/`、`sillytavern-web/stapk-capabilities.json`、`api-contract.json`、`stapk-web-manifest.json`、`transform-report.json`，并通过 no-node verifier。
- Task 11 已完成 capability runtime、fail-closed Web helper、核心入口可见性测试和严格 contract 收敛。2026-07-21 正式 Web 资源扫描为 `implemented=98`、`external_optional=136`、`unsupported_hidden=105`、`needs_review=0`；不带豁免的 `verify:no-node-capabilities` 已通过，`visibleNeedsReview=[]`、`unassignedEndpoints=[]`。
- Task 12 已建立 `npm run build:no-node-apk -- --variant <debug|release> --ref <ref>` 单命令链路，并让 CI/Release 统一上传 APK、checksum、API contract、capability runtime、Web manifest 和 transform report。2026-07-21 streaming 最终修复后的本地 Debug 链路 79/79 no-node tests、strict capability（`ok=true`、`visibleNeedsReview=[]`、`unassignedEndpoints=[]`）、Android JVM tests（303 tests，0 failures、0 errors、2 skipped）和 assemble 全部通过，最新 output APK SHA-256 为 `4322b4a8e47b93bddf03fec13956915e296d76065149a0c58a7332838f4e1077`。
- Android 侧已落地 `NativeAdapterPaths`、`NativeAdapterStatus` 和 `NativeAdapterState`，并用 JVM 单元测试固定 no-node 私有目录、派生文件路径和状态默认值。
- Android 侧已落地 loopback-only `NativeHttpServer`、静态资源服务和 foreground `NativeHttpService` 骨架；JVM 测试覆盖真实 `/version`、首页、MIME、目录边界和启动错误收敛。
- Task 5 已把正式 transform assets 接入 APK，运行时在后台线程按 `stapk-web-manifest.json` 首次安装或升级刷新 `filesDir/web`；MainActivity 启动并绑定原生服务后加载 `http://127.0.0.1:<port>/`。2026-07-10 已在 Pixel 8 / Android 15 模拟器验证前台 Service、Web assets 安装升级、`/version`、首页、Webpack bundle、Back 后服务存活和无 Node 进程；当时发现的 `/csrf-token` 边界已由 Task 6 解决。SAF 文件选择、外部 HTTPS 跳转以及下载/导出 SAF 均已接入。
- Task 6 已实现 `/version`、`/csrf-token`、`/api/ping`、`/api/settings/get` 和 `/api/settings/save`。settings 响应采用 upstream 实际要求的 envelope，持久化后仍强制 OpenAI-compatible provider，但不再覆盖 preset 的 `stream_openai`；新安装默认关闭 streaming，用户和 preset 可独立保存 `true` 或 `false`。2026-07-11 的 settings 持久化设备验收继续有效；2026-07-21 Pixel 8 / Android 15 模拟器已用匿名 preset A/B 分别确认 `stream=true` 与 `stream=false` 在切换和应用重启后独立恢复，证据只记录 selected/stream boolean 与 restart 方式。
- Task 7 已实现角色 get/all/create/edit/delete/chats、聊天 get/save/delete/search、默认 avatar/thumbnail 路由和真实 multipart 表单适配。角色内部存 JSON、对外保持 `.png` identity，聊天保持 JSONL。2026-07-11 先卸载旧 app 后在 Pixel 8 / Android 15 模拟器干净安装，官方首页到达 `app_ready`；设备上创建的 `Device Alice`、默认头像和 `device-chat.jsonl` 在 app 重启后仍可读取，Character Management 与角色编辑页均可显示，Node 进程数为 0。welcome recent chats 和 tokenizer/token count 仍是后续兼容范围。
- Task 8 已实现 upstream secrets read/write/delete、OpenAI/custom source、`/models` status，以及 streaming/non-streaming `/chat/completions` 转发。非流式继续返回完整 JSON；流式通过 OkHttp `ResponseBody` 到 NanoHTTPD chunked response 透明转发 provider SSE。明确 JSON 响应会在 1 MiB 上限内完整解析，仅接受 object/array，重新序列化为单行 `data:` 事件并追加 `[DONE]`；畸形 JSON 或 primitive top-level 返回安全 502。流关闭会关闭 response 并取消 OkHttp call；stream terminal diagnostics 以严格 enum 记录 `completed`、`canceled` 或 `read_error` 且只回调一次。diagnostics 只记录脱敏元数据，不记录 API key、prompt、SSE chunk、生成正文或 provider 错误原文。2026-07-11 与 2026-07-17 的非流式、持久化和 secret 脱敏设备验收继续有效；2026-07-21 Pixel 8 / Android 15 模拟器已通过真实 custom provider 的 non-streaming、streaming 增量显示、停止生成、重启持久化与 diagnostics 验收，证据只记录 boolean、HTTP status/MIME、文本长度、事件 code 和安全字段名，不记录任何聊天正文；terminal diagnostics 补充由 JVM 测试验证，未声称在该真实 provider 流程中重新观察。
- 基础 MVP 的 Task 9 已落地固定 Android patch queue，Task 11 及后续兼容修复已扩展为 11 个可审计 patch。Patch 只应用于构建工作树，不修改 upstream checkout；Persona、World Info、群组、recent chats、背景、附件、导入导出、主题和 preset 等已实现核心入口保持可见，Extensions marketplace、本地模型和 multiuser 继续隐藏，远程图片、语音、向量、字幕和翻译按 capability 显示外部服务说明。2026-07-16 API 35 冷启动复核中，第 3 秒仍显示原生启动页而非黑色 WebView，第 13 秒官方 UI 可用，`app_ready` 约 12.1 秒；黑色 surface 验收通过，冷启动总耗时保留为性能优化项。
- 2026-07-12 将项目主体完成标准提升为“官方单用户功能基本全量”：补齐所有普通用户可见核心 UI 对应的原生能力；远程 embedding、图片、TTS、STT 采用可选 capability；本地重型模型和任意 Node 扩展明确排除。旧数据迁移降级为主体完成后的可选独立项目。
- 2026-07-16 已完成 SAF 数据导出桥及审查加固：只允许已知 `127.0.0.1:<port>` 主文档注入随机 nonce；browser-generated staging 必须携带同一 nonce header，并受单文件 32 MiB、活动 ticket 数和总暂存字节配额约束；文件名、MIME 与扩展名必须匹配业务白名单。角色/聊天等服务端导出复用单次 ticket，World Info、附件等浏览器生成内容通过 `/api/stapk/exports/create` 暂存后交给 `ACTION_CREATE_DOCUMENT`，异步失败会显示用户可见错误。Activity 配置变化期间的 SAF 结果会保留到服务重绑后再消费 ticket。
- Pixel 8 / Android 15 模拟器先卸载旧 app 后干净安装，官方首页正常加载；World Info JSON 在普通保存和 SAF 页面旋转后的配置变化场景均生成 14 字节 `{"entries":{}}`，私有 exports 目录随后为空，无崩溃且只有 `com.stapk.mobile` 进程。经 `adb forward` 对 staging endpoint 发起不带 nonce 的直接 POST，返回 `403 export_forbidden`。角色 JSON 与 World Info JSON 重新导入、完整官方单用户 UI 矩阵、真实外部 provider 和黑色 WebView surface 计时均已完成 API 35 验收；最新 output APK 再次 clean install 后 `/version` 返回 `node_runtime=false`。项目正式维护范围为 API 35 及以上，Android 7–14 不再纳入支持矩阵，详见 `docs/plan/2026-07-12-stapk-single-user-feature-validation-record.md`。
- 完整应用数据 ZIP 备份恢复、恢复回滚和 Data Maid 不属于主体完成门槛，推迟到主体功能与发布链路全部完成后的独立可选项目；聊天自身的历史快照/恢复与普通角色、聊天、World Info 导入导出不受此决策影响。
- v0.3.2 已实现 OpenAI/Custom OpenAI-compatible 远程 Embedding、本地 SQLite Vector Storage、Data Bank、聊天记忆和 World Info RAG；各 RAG 开关默认关闭并受隐私确认保护。

## 目标

### 产品目标

- 用户打开 app 后直接看到 SillyTavern 官方 Web UI。
- 基础 MVP 可创建或加载角色，保存设置和聊天，并通过 OpenAI-compatible provider 完成真实对话。
- 项目主体完成时，官方单用户核心 UI 中可见、可点击的入口必须真实可用；未实现能力必须隐藏或显示明确的外部服务要求，不允许点击后返回 404/501。
- 普通用户不看到 Node、npm、payload、server.js、端口、控制面板或终端概念。
- 用户数据保存在 app 私有目录。
- App 可以离线打开 UI 和本地数据；真实对话需要用户配置 OpenAI-compatible API。

### 工程目标

- 从指定 upstream ref 生成 Android 可打包资源。
- 运行时 APK 不包含 Node runtime，不包含 `node_modules`。
- 转换产物能追溯 upstream commit、patch queue hash、前端资源 hash、API 契约版本。
- Android 原生层实现一组明确、可测试的 SillyTavern API 兼容接口。
- 每次 upstream 更新都能通过契约扫描发现前端新增或变更的 `/api/...` 调用。
- 失败必须可诊断：转换失败、patch 冲突、前端 API 契约漂移、原生接口未实现、provider 请求失败都要有明确报告。
- 运行时诊断已于 2026-07-16 落地：HTTP 4xx/5xx、Provider、存储隔离和恢复失败写入脱敏 JSONL；日志按 2 MiB 轮转并保留 3 份，summary/export 不返回用户业务内容。完整应用备份恢复仍保持为主体完成后的可选项。

### MVP 验收目标

第一版 MVP 只要求以下闭环：

```text
安装 APK
    -> 打开 app
    -> WebView 加载官方 SillyTavern UI
    -> settings 可读写
    -> character 可列出、读取、创建或编辑
    -> chat 可读取、保存、删除或搜索
    -> 配置 OpenAI-compatible key/base URL/model
    -> 发送一条消息
    -> 原生层请求 OpenAI-compatible API
    -> 回复写入聊天记录
    -> 重启 app 后角色、设置和聊天仍存在
```

### 项目主体完成标准

基础 MVP 只是可运行起点。项目主体完成采用“官方单用户功能基本全量”标准，不以实现 SillyTavern 全部服务端 endpoint 为目标。

主体必须完成：

- settings、主题、生成参数和本地 preset 的持久化。
- Persona、角色、角色卡、群组和群聊的创建、编辑、删除、搜索与持久化。
- PNG/JSON 官方角色卡导入导出，以及 PNG/JPEG/WebP 头像和缩略图处理。
- 普通聊天、群聊、聊天搜索、重命名、删除、导入导出和单聊天历史快照恢复。
- World Info / Lorebook 的创建、编辑、导入导出、角色/Persona/聊天绑定和生成时注入。
- 背景的列出、上传、删除、重命名、文件夹管理和持久化。
- 附件、上传、下载和导出所需的 Android SAF 桥接。
- 官方 UI 正常运行所需的 tokenizer、token count、recent chats、文件和诊断 endpoint。
- OpenAI-compatible preset 可选 streaming/non-streaming 对话、错误透传、secret 脱敏和重启持久化。
- 所有普通用户可见入口与原生兼容层能力一致；能力不可用时必须隐藏或明确降级。

项目主体完成不要求：

- 在 APK 内运行本地 embedding、Stable Diffusion、Whisper、TTS 或其他大型模型。
- 在 APK 内引入 Node.js、Python、Ollama、任意 Shell、npm 插件运行时或通用服务端脚本环境。
- 支持扩展市场、第三方扩展安装或允许扩展执行任意服务端代码。
- 实现多用户、登录页、远程访问或公网监听。
- 与 SillyTavern Node 服务端所有内部 endpoint 逐项等价。

远程增强能力可以在主体功能稳定后增量提供：

- 远程 embedding + Android 本地向量索引和相似度查询。
- 远程图片生成、TTS、STT、caption、translation。
- 仅当用户配置对应外部服务后显示入口；未配置时隐藏或显示“需要外部服务”。

“转换成功”与“功能完成”必须分开判定：转换器和 Gradle 能产出 APK 只证明构建链路成功；只有主体必做功能的 UI-action/API contract 全部通过，才可标记项目主体完成。

## 非目标

- 不做通用 Node 项目到 Android 原生转换器。
- 不在 APK 运行 Node.js、npm、server.js。
- 不要求实现 SillyTavern 全量后端 API，只实现官方单用户核心 UI 实际依赖的兼容接口。
- 不支持所有 provider，主体对话仍以 OpenAI-compatible 为正式支持范围。
- 不在第一版支持扩展市场、第三方扩展安装、插件热更新。
- 不在 APK 内捆绑或运行本地 embedding、图片、语音等重型模型；远程能力按独立模块接入。
- 不承诺依赖 Node、Python、Shell、Git、FFmpeg 桌面进程或任意原生插件的 upstream 功能可直接转换。
- 不在第一版支持多用户、登录页、远程访问、公网监听。
- 不修改 upstream 仓库本身；允许在构建工作目录应用固定 patch queue。

## 架构选择

### 选择：Kotlin 本地 HTTP 兼容层

推荐采用 Android 原生本地 HTTP server，监听 `127.0.0.1` 的随机端口或固定私有端口。WebView 仍按浏览器形态加载 SillyTavern UI，并通过相对路径请求 `/api/...`。

```text
Android App
├── WebView
│   └── SillyTavern official public/ UI
├── NativeHttpService
│   ├── StaticAssetController
│   ├── SettingsController
│   ├── CharacterController
│   ├── ChatController
│   ├── SecretsController
│   └── OpenAiCompatibleController
├── Storage layer
│   ├── settings.json
│   ├── characters/
│   ├── chats/
│   ├── secrets/
│   └── logs/
└── Android platform bridges
    ├── SAF import/export
    ├── WebView file chooser
    ├── network client
    └── diagnostics
```

这条路线的理由：

- SillyTavern 官方 Web UI 当前天然通过 HTTP 和 `/api/...` 通信。
- 保留 HTTP 形状能最大限度减少前端 patch。
- Kotlin/Java 可以直接实现本地文件读写、OpenAI-compatible 请求、诊断日志和 Android 生命周期。
- 未来如果要逐步扩展 provider 和 endpoint，可以按 controller 增量补齐。

### 排除：WebViewAssetLoader 承接全部 API

`WebViewAssetLoader` 适合静态资源，但不适合作为完整后端：

- POST body 解析、multipart 上传、长响应、错误映射会复杂。
- OpenAI streaming/SSE 后续支持困难。
- 和 SillyTavern 前端现有 HTTP API 模型偏离。

可以后续评估它只承接静态资源，但 MVP 不把它作为唯一后端机制。

### 排除：JS bridge 替换 fetch

通过 patch 前端 `fetch()`，把 API 调用改成 Android `JavascriptInterface`，理论上可以去掉本地端口，但缺点明显：

- patch 面大，容易和 upstream 前端冲突。
- `ReadableStream`、文件上传、下载、错误码、cookie/CSRF 模拟会很麻烦。
- 前端代码会更像 stAPK fork，而不是官方 UI 转换产物。

因此不作为 MVP 方案。

## 转换流水线

构建期流程：

```text
1. 输入 upstream repo/ref
2. git fetch 干净源码
3. 记录 resolved commit
4. 应用 patch queue
5. 扫描 public/ 静态资源
6. 扫描前端 fetch('/api/...') 调用
7. 生成 api-contract.json
8. 生成 Android assets:
   - sillytavern-web/
   - stapk-web-manifest.json
   - api-contract.json
   - transform-report.json
9. Gradle 打包 APK
```

运行时流程：

```text
1. MainActivity 启动 NativeHttpService
2. NativeHttpService 绑定 loopback 地址
3. NativeHttpService 提供静态资源和 /api/... 兼容接口
4. WebView 加载 http://127.0.0.1:<port>/
5. Web UI 通过 fetch('/api/...') 访问原生兼容层
6. 原生兼容层读写 app 私有目录或请求 OpenAI-compatible provider
```

转换产物不再包含：

```text
node
npm
node_modules/
runtime-android-arm64-node*.zip
payload.tgz
server.js 运行入口
```

转换产物应包含：

```text
mobile/app/src/main/assets/sillytavern-web/
mobile/app/src/main/assets/stapk-web-manifest.json
mobile/app/src/main/assets/api-contract.json
mobile/app/src/main/assets/transform-report.json
```

## Patch Queue 策略

允许修改一部分固定源码，但必须限定为平台边界 patch。

允许 patch：

- 注入 `stapkMobile` 或类似 platform flag。
- 关闭登录、多用户、CSRF 等桌面 server 语境功能。
- 隐藏第一版不支持的 provider 或功能入口。
- 把默认 provider 设为 OpenAI-compatible。
- 把新安装默认 streaming 设为 false，但不得覆盖用户或 preset 已保存的 `stream_openai`。
- 为 Android WebView 修复文件选择、下载、外链、viewport 或资源路径问题。
- 让前端在缺失某些非 MVP endpoint 时显示降级状态，而不是卡死。

禁止 patch：

- 改写聊天主体算法，除非是为了绕过 Node-only API 且有契约说明。
- 大范围重写 UI。
- 把 stAPK 专用业务逻辑混入 upstream 核心模块。
- 删除官方功能但不在 transform report 中声明。
- 让 patch 依赖本地手工修改。

每个 patch 必须有说明：

```text
patch 文件名
修改 upstream 文件
为什么需要
是否影响桌面版行为
是否可以由 Android 原生层替代
upstream 更新冲突时如何处理
```

## Android 运行时组件

### NativeHttpService

职责：

- 启动和停止本地 HTTP server。
- 绑定 `127.0.0.1`，不监听公网地址。
- 记录当前端口、状态和错误。
- 向 Activity 暴露状态：`STARTING`、`RUNNING`、`FAILED`、`STOPPED`。
- 在前台服务中运行，避免 Activity 重建导致兼容层停止。

第一版可以不提供公网访问、不提供外部浏览器入口。

### StaticAssetController

职责：

- 从 assets 或首次复制后的 app 私有目录提供 `index.html`、JS、CSS、图片、字体、locale 等静态资源。
- 支持正确的 MIME type。
- 支持 `Cache-Control`，但开发阶段可禁用缓存。
- 支持 `/` 返回 `index.html`。
- 支持缺失资源的诊断日志。

建议目录：

```text
filesDir/web/
filesDir/state/installed-web-manifest.json
```

是否必须复制到 `filesDir/web/` 需要实现阶段验证。若 HTTP server 可以稳定从 assets stream 提供资源，可避免首次复制；若 MIME、range、性能或缓存处理困难，则首次复制到私有目录。

### SettingsController

MVP endpoint：

```text
POST /api/settings/get
POST /api/settings/save
```

职责：

- 返回 SillyTavern 前端期望的 settings JSON。
- 保存用户设置到 app 私有目录。
- 提供第一版默认值：
  - `main_api = openai`
  - `chat_completion_source = openai` 或 custom OpenAI-compatible source
  - `stream_openai = false`
  - 默认模型可配置
- 对未知字段采用保留策略：前端传入但原生层不理解的字段应原样保存，避免设置丢失。

建议存储：

```text
filesDir/user_config/settings.json
filesDir/user_config/openai-settings.json
```

### SecretsController

MVP endpoint 需要在 API 盘点阶段精确确认。目标能力：

- 保存 OpenAI-compatible API key。
- 读取 key 的存在状态，但不向前端明文返回 key。
- 支持自定义 base URL 和模型名。

建议策略：

- API key 存 Android 私有目录，后续迁移到 `EncryptedSharedPreferences` 或 Android Keystore 包装。
- 诊断日志不得打印 key。
- transform report 和 validation record 不得包含 key。

### CharacterController

MVP endpoint：

```text
POST /api/characters/all
POST /api/characters/get
POST /api/characters/create
POST /api/characters/edit
POST /api/characters/delete
POST /api/characters/chats
```

职责：

- 维护角色列表。
- 读取和保存角色卡数据。
- 维护 avatar 文件名或占位图。
- 返回前端期望的字段形状。

MVP 可以先支持 JSON 角色数据和默认头像。完整 PNG/JSON 角色卡导入导出、PNG metadata 解析以及 PNG/JPEG/WebP 头像媒体处理可放后续阶段。

建议存储：

```text
filesDir/user_data/characters/
filesDir/user_data/characters/<character-id>.json
filesDir/user_data/characters/avatars/
```

### ChatController

MVP endpoint：

```text
POST /api/chats/get
POST /api/chats/save
POST /api/chats/delete
POST /api/chats/search
```

职责：

- 读取指定角色的聊天记录。
- 保存聊天记录。
- 删除聊天。
- 支持最小搜索能力。
- 保持和 SillyTavern 前端期望的数据结构兼容。

建议存储：

```text
filesDir/user_data/chats/
filesDir/user_data/chats/<character-id>/<chat-name>.jsonl
filesDir/user_data/chats/metadata/
```

为了降低兼容风险，MVP 应优先保持 SillyTavern 现有 JSONL 习惯，而不是创造新格式。

### OpenAiCompatibleController

MVP endpoint：

```text
POST /api/backends/chat-completions/status
POST /api/backends/chat-completions/generate
```

职责：

- 根据 settings/secrets 读取 base URL、API key、model。
- 将 SillyTavern 前端生成请求转换为 OpenAI Chat Completions 请求。
- 请求 OpenAI-compatible API。
- 非流式返回 SillyTavern 前端期望的完整 JSON 响应。
- 流式原样转发 provider SSE 字节流，不在 Kotlin 中解析或改写正常 SSE event。
- provider 在 `stream=true` 时明确返回 JSON，则将完整 JSON 包装为一个 SSE `data:` 事件并追加 `[DONE]`，避免前端产生空消息。
- 新安装仍默认关闭 streaming，preset 可以独立启用并持久化 streaming。

第一版支持：

```text
OpenAI official: https://api.openai.com/v1
OpenRouter: https://openrouter.ai/api/v1
DeepSeek 或其他兼容服务：用户自定义 base URL
```

当前仍不支持：

```text
tool calling 完整闭环
multimodal 图片输入
reasoning metadata 完整保留
provider 特有 headers 自动补齐
模型列表高级分组
```

OpenRouter 等服务需要额外 header 时，可以在 MVP 中提供保守默认：

```text
Authorization: Bearer <key>
Content-Type: application/json
HTTP-Referer: https://github.com/zaixiakongyiji/stapk-termux
X-Title: stAPK Mobile
```

具体 header 必须在实现阶段用 provider 文档和真实请求验证。

## API 兼容契约

转换器必须生成 `api-contract.json`，至少包含：

```json
{
  "schema_version": 1,
  "upstream": {
    "repo": "https://github.com/SillyTavern/SillyTavern.git",
    "ref": "release",
    "commit": "<resolved-commit>",
    "version": "<sillytavern-version>"
  },
  "frontend_api_calls": [
    {
      "method": "POST",
      "path": "/api/settings/get",
      "source": "public/script.js",
      "status": "implemented"
    }
  ],
  "unsupported_api_calls": [],
  "patch_queue_hash": "<sha256>",
  "web_assets_hash": "<sha256>"
}
```

契约扫描规则：

- 扫描 `fetch('/api/...')`、`fetch("/api/...")`、动态 `getGenerateUrl()` 等已知模式。
- 对动态 URL 做保守标记，无法解析时写入 `needs_review`。
- 任一新发现的 `/api/...` 调用未在兼容层 manifest 中声明时，CI 应失败或至少在 strict 模式失败。
- MVP 允许存在 unsupported endpoint，但必须由 transform report 明确列出，并且对应 UI 入口应被 patch 隐藏或降级。
- 项目主体完成后，所有普通用户可见 UI action 必须映射到 `implemented` endpoint；暴露路径中的 `needs_review` 或 unsupported 必须使 strict gate 失败。
- 被明确排除的本地模型、第三方扩展和多用户路径可以保留为 unsupported，但必须有稳定 capability ID、隐藏规则和自动化测试，不能只依赖人工确认。

## MVP Endpoint 清单

第一版最低实现清单：

| 分类 | Endpoint | 必要性 |
|------|----------|--------|
| 系统 | `GET /version` | 前端启动读取版本 |
| 系统 | `GET /csrf-token` | 如果前端仍请求，返回固定兼容 token 或通过 patch 关闭 |
| 系统 | `POST /api/ping` | 前端 session/状态心跳 |
| 静态 | `GET /` | 返回 `index.html` |
| 静态 | `GET /public/*` 或等价路径 | 前端资源 |
| 设置 | `POST /api/settings/get` | 启动和设置页 |
| 设置 | `POST /api/settings/save` | 设置持久化 |
| 角色 | `POST /api/characters/all` | 角色列表 |
| 角色 | `POST /api/characters/get` | 读取角色 |
| 角色 | `POST /api/characters/create` | 创建角色 |
| 角色 | `POST /api/characters/edit` | 编辑角色 |
| 角色 | `POST /api/characters/delete` | 删除角色 |
| 角色 | `POST /api/characters/chats` | 角色聊天列表 |
| 聊天 | `POST /api/chats/get` | 读取聊天 |
| 聊天 | `POST /api/chats/save` | 保存聊天 |
| 聊天 | `POST /api/chats/delete` | 删除聊天 |
| 聊天 | `POST /api/chats/search` | 基础搜索 |
| Secrets | `POST /api/secrets/read`、`/write`、`/delete` | 保存、读取和删除 OpenAI-compatible key 状态 |
| OpenAI | `POST /api/backends/chat-completions/status` | 验证模型/API 可用 |
| OpenAI | `POST /api/backends/chat-completions/generate` | 真实生成回复 |

实现阶段必须对每个 endpoint 补充：

```text
请求体样例
响应体样例
错误响应
对应前端调用文件
是否需要 patch
测试用例
```

## 单用户核心能力补齐清单

后续实施不按 217 个 `needs_review` endpoint 平铺重写，而按用户能力域逐组收敛。每个能力域先扫描官方 UI action 和真实请求，再确定原生 controller、存储格式和 patch/capability gate。

| 能力域 | 主体要求 | 处理方式 |
|--------|----------|----------|
| Persona | 创建、编辑、切换、头像、绑定 Lorebook | Android 原生存储与 endpoint |
| 角色卡 | CRUD、PNG/JSON 角色卡导入导出、PNG/JPEG/WebP 头像/缩略图 | Android 原生解析、SAF |
| 群组 | 群组 CRUD、成员管理、群聊持久化 | Android 原生 controller |
| 聊天 | 普通/群聊、recent、搜索、重命名、导入导出、单聊天历史快照恢复 | JSONL + 索引 + SAF |
| World Info | CRUD、导入导出、绑定、生成时读取 | Android 原生 controller |
| 背景 | 列表、上传、删除、重命名、文件夹 | Android 原生文件管理 |
| 附件与文件 | 上传、下载、导出、文件元数据 | 本地 HTTP + SAF bridge |
| Tokenizer | UI 需要的 token count 和 tokenizer 路由 | Kotlin/Java 库或构建期前端 bundle，不引入通用运行时 |
| 数据管理 | 诊断和损坏文件保留；完整应用备份恢复在主体完成后可选开发 | Android 原生实现；可选能力单独立项 |
| 远程 AI 增强 | embedding、图片、TTS、STT 等 | 外部 HTTP API，可选模块 |
| 本地重型模型 | embedding、图片、语音模型推理 | 主体范围明确不支持 |

能力域完成条件：

1. 列出该能力对应的官方 UI action、endpoint、动态 URL 和静态资源。
2. 普通路径上的 endpoint 全部实现并有请求/响应 fixture。
3. 文件写入仅发生在 app 私有目录或用户授权的 SAF URI。
4. 设备上从 UI 完成至少一次真实操作并验证重启持久化。
5. contract 中该能力域不再有暴露的 `needs_review`；排除路径有 capability gate 测试。

## 数据目录设计

新安装目录：

```text
filesDir/
├── web/
│   └── SillyTavern public assets
├── user_config/
│   ├── settings.json
│   └── provider-openai-compatible.json
├── user_data/
│   ├── characters/
│   ├── chats/
│   ├── groups/
│   ├── personas/
│   ├── world_info/
│   ├── backgrounds/
│   ├── uploads/
│   ├── backups/
│   └── exports/
├── secrets/
│   └── provider-secrets.enc
├── logs/
│   ├── native-http.log
│   ├── api.log
│   └── provider.log
└── state/
    ├── native-adapter-state.json
    ├── installed-web-manifest.json
    └── migration-state.json
```

原则：

- 用户数据和 Web UI 资源分离。
- Web UI 更新不得覆盖用户数据。
- settings 保存未知字段，防止前端升级后字段丢失。
- secrets 与普通 settings 分离。
- logs 默认不记录完整 prompt、response 和 API key；调试模式可由用户显式开启。

## 0.2.0 / 旧 Node 容器路线迁移（主体完成后的可选项）

旧数据迁移不再是项目主体完成或首次发布的阻断条件。当前开发和发布按全新安装处理；项目主体功能、UI/API 对齐、CI/Release 和最终验证全部完成后，再单独评估是否开发 0.2.0 数据迁移。

如果未来决定提供兼容迁移，必须作为独立设计、独立实施计划和独立发布风险处理，不得与主体功能补齐交叉修改。

旧数据可能位于：

```text
filesDir/SillyTavern/data/
filesDir/SillyTavern/config.yaml
filesDir/SillyTavern/public/scripts/extensions/third-party/
```

新路线迁移目标：

```text
filesDir/user_data/
filesDir/user_config/
filesDir/secrets/
```

迁移策略：

1. App 首次启动时检查旧目录。
2. 如果发现旧 `data/`，进入迁移流程。
3. 先复制到 `filesDir/state/migration-work/`。
4. 校验角色、聊天和 settings 是否能被原生层解析。
5. 成功后移动到新目录并写入 `migration-state.json`。
6. 失败时不删除旧数据，显示诊断页。
7. 已完成迁移后不得再次覆盖用户新数据。

未实现迁移时的发布约束：

- 0.3.0 不承诺从 0.2.x 原地升级，发布说明必须明确建议全新安装。
- 不读取、不删除、不自动改写旧 `filesDir/SillyTavern/`。
- 不把 migration checkbox、空 manager 或未经验证的复制逻辑作为兼容能力发布。

## Android 依赖选择

本设计不提前锁死 HTTP server 库，实施前必须做小型 spike。候选：

| 方案 | 优点 | 风险 |
|------|------|------|
| NanoHTTPD | 小、容易嵌入、适合 MVP，可通过 chunked response 转发 SSE | multipart、大文件、断连和错误处理需要显式封装 |
| Ktor Server | Kotlin 生态完整，路由和 JSON 更舒服 | Android 运行兼容性、包体、协程和生命周期需要验证 |
| 自研最小 HTTP server | 可控，能只做需要的行为 | 容易漏 HTTP 细节，维护成本高 |

MVP 建议：

- 先用 spike 验证 NanoHTTPD 能否稳定支持静态资源、POST JSON、基础文件上传。
- 新安装 streaming 默认关闭，但服务端同时支持 preset 可选 streaming/non-streaming。
- streaming 使用 OkHttp 流式 body 与 NanoHTTPD chunked response；流关闭必须同步取消上游 call。
- 如果 NanoHTTPD 卡在 multipart 或响应流，切换 Ktor Server 或自研最小实现。

JSON 序列化可优先用 Kotlinx Serialization 或 Moshi。网络请求 OpenAI-compatible 可用 OkHttp。

## WebView 策略

WebView 行为：

- 加载本地 HTTP 地址：`http://127.0.0.1:<port>/`。
- 不打开公网明文流量。
- 外部 HTTPS 链接交给系统浏览器。
- 文件选择使用 Android SAF。
- 下载/导出使用 Android SAF。
- Back 键优先 WebView history，无法返回时 `moveTaskToBack(true)`。

安全要求：

- 本地 HTTP server 只监听 loopback。
- 不提供局域网访问。
- 不允许任意 app 通过公网访问。
- SAF JavaScript bridge 和 `/api/stapk/exports/create` 使用 Activity 随机 nonce 双向约束；端口未知、端口不匹配或 `localhost` 主机均不得注入 bridge nonce。其他 loopback API 的统一会话认证若未来需要扩大，必须单独设计，不能复用可被普通 API 响应泄漏的固定 CSRF token。
- provider streaming diagnostics 只允许记录 `host`、`status`、`durationMs`、`stream`、结束类型和异常类；禁止记录 Authorization、API key、请求 messages、SSE chunk、生成正文和 provider 错误原文。

## 错误处理

转换阶段错误：

| 错误 | 行为 |
|------|------|
| upstream ref 不存在 | 转换失败，输出 repo/ref |
| patch 冲突 | 转换失败，输出 patch 和冲突文件 |
| `public/index.html` 缺失 | 转换失败 |
| 必要前端资源缺失 | 转换失败 |
| 前端新增未声明 API | strict 模式失败 |
| patch 隐藏功能未覆盖 unsupported API | strict 模式失败 |

运行阶段错误：

| 错误 | UI |
|------|----|
| 本地 HTTP server 启动失败 | 启动失败页，复制诊断 |
| Web asset 缺失 | 启动失败页，列出缺失文件 |
| settings 解析失败 | 诊断页，可重置 settings |
| character/chat 数据损坏 | 诊断页，保留原文件 |
| OpenAI-compatible key 缺失 | UI 提示配置 API key |
| provider 请求失败 | 前端显示错误，日志记录 HTTP status 和脱敏 message |
| API 未实现 | 返回明确 JSON 错误，transform strict 应防止 MVP 路径出现 |

## 测试策略

### 转换器测试

- 输入固定 upstream ref，生成 web assets。
- `api-contract.json` 包含 MVP endpoint。
- 任一前端新增 API 未声明时 strict 校验失败。
- patch queue hash 稳定。
- 输出不包含 `node_modules`、Node binary、runtime zip。
- 每个主体能力域都有 UI-action/API mapping fixture。
- 可见 UI action 出现 unknown、unsupported 或 `needs_review` endpoint 时 strict gate 失败。
- 明确排除的能力必须有 capability gate，验证入口隐藏或显示外部服务要求。

### Kotlin 单元测试

- Settings 读写保留未知字段。
- Character 创建、读取、编辑、删除。
- Chat JSONL 读写。
- Secrets 不明文出现在 settings 响应。
- OpenAI-compatible 请求体转换。
- Provider 错误响应映射。
- Persona、群组、World Info、背景、附件、单聊天历史快照恢复和角色卡导入导出。
- 文件损坏、非法路径、重复文件名、存储空间不足和中断写入恢复。

### Android 集成测试

- 启动 NativeHttpService 后 `/version` 可访问。
- WebView 能加载首页。
- `/api/settings/get`、`/api/characters/all`、`/api/chats/get` 返回前端可解析 JSON。
- 创建角色后重启 app，角色仍存在。
- 保存聊天后重启 app，聊天仍存在。
- 角色卡、World Info、背景和附件经 SAF 导入导出后可再次读取。
- 所有顶层导航和普通菜单入口执行 smoke test，不允许出现 404/501。

### 真机验证

最低真机记录：

```text
安装 APK
启动 app
WebView 显示 SillyTavern UI
创建角色
配置 OpenAI-compatible key/base URL/model
发送消息
收到回复
重启 app 后聊天存在
确认 adb shell run-as com.stapk.mobile ls files/ 不存在 runtime/node/node_modules
遍历主体功能验收矩阵，记录每个 UI action 的结果和持久化证据
```

## 分阶段路线

### Phase 0：新设计冻结

输出：

- 本设计文档。
- 用户确认后的实施计划。
- 旧 Node 容器 completion plan 标记为废弃或历史参考。

验收：

- 文档明确 APK 无 Node。
- 文档明确保留官方 Web UI。
- 文档明确第一版只支持 OpenAI-compatible。
- 文档明确不是通用 Node 转换器。

### Phase 1：API 契约盘点和转换器改向

目标：

- 转换器从生成 `payload.tgz` 改为生成 `sillytavern-web/`。
- 生成 `api-contract.json`。
- 生成 unsupported API 报告。

验收：

- 输出不含 Node runtime、node_modules。
- `api-contract.json` 能列出 MVP endpoint。
- strict 校验能阻断未声明 API。

### Phase 2：原生 HTTP 兼容层 Spike

目标：

- 选定 Android HTTP server 方案。
- 实现静态资源、`/version`、`/api/ping`、`/api/settings/get`。
- WebView 能打开首页。

验收：

- 真机或模拟器能加载 UI。
- 不启动 Node。
- `RuntimeManager` 的 Node 相关逻辑不再参与新入口。

### Phase 3：本地数据 MVP

目标：

- 实现 settings、characters、chats 的最小读写。
- 支持创建角色、保存聊天、重启恢复。

验收：

- 用户可以在官方 UI 中创建或选择角色。
- 用户可以保存一条聊天。
- 重启 app 后数据存在。

### Phase 4：OpenAI-compatible 真实对话

目标：

- 实现 secrets 和 OpenAI-compatible generate/status。
- 支持非 streaming 真实生成。

验收：

- 配置 key/base URL/model。
- 发送一条消息并收到回复。
- 聊天记录保存。
- provider 错误能显示可理解信息。

### Phase 5：官方单用户核心功能补齐

目标：

- 补齐 Persona、角色卡导入导出、群组、World Info、背景、附件、tokenizer、诊断和普通业务数据导入导出。
- 建立 UI-action/API contract，收敛普通用户路径中的 `needs_review`。
- 完成下载/导出 SAF、重新导入验证和 WebView 黑色 surface 回归。

验收：

- 主体功能矩阵中的每个普通 UI action 都有原生实现和设备证据。
- 普通路径不出现 404/501；明确排除能力有 capability gate。
- 重启后角色、Persona、群组、聊天、World Info、背景和设置仍存在。

### Phase 6：远程增强能力与能力门控（可选，不阻断主体完成）

目标：

- 设计统一 remote capability/provider 接口。
- 可选支持远程 embedding/vector、图片生成、TTS、STT、caption、translation。
- 不配置外部服务时隐藏入口或显示明确要求。

验收：

- 远程能力不会引入 Node、Python、Ollama 或本地重型模型。
- provider 错误、超时、限流和 secret 脱敏有测试。
- 关闭远程能力后主体功能不受影响。

### Phase 7：发布验证和文档收口

目标：

- CI/Release 使用新转换产物。
- 生成主体功能验证记录、API contract 和 capability report。
- README、CLAUDE、设计文档和实施计划只描述 no-node 当前事实。

验收：

- APK 资产不含 Node runtime。
- release artifact 包含 web manifest、api contract、transform report。
- clean install 设备验证覆盖主体功能矩阵。
- 外部 provider 和远程增强能力分别标明已验证范围。

### Phase 8：旧数据迁移（项目主体完成后的可选项）

仅在主体功能和发布收口完成后决定是否启动。若启动，必须重新确认旧版本真实目录、数据格式、用户规模、升级渠道和回滚策略；当前设计不把它计入项目主体完成定义。

### Phase 9：完整数据备份恢复与 Data Maid（项目主体完成后的可选项）

该阶段与普通角色、聊天、World Info 等业务文件的 SAF 导入导出不同。它面向整应用 `user_config/`、`user_data/` 的 ZIP 归档、manifest/hash 校验、恢复预检、事务式替换、失败回滚和孤儿数据维护。只有主体功能、发布链路和设备矩阵全部收口后才重新评估是否开发；不实现该阶段不影响 0.3.0 主体完成、Checkpoint 或发布验收。

若未来启动，必须单独产出设计与实施计划，并继续遵守：不包含 secrets、logs、web 和 state；恢复前必须预检且不得提前写用户目录；恢复失败必须回滚；Data Maid 只能删除用户明确确认且 hash 未变化的文件。该阶段不得与旧 0.2.x 数据迁移合并，两者风险和输入格式不同。

## 风险

### API 面比预期大

SillyTavern 前端在启动和发送消息过程中可能调用很多非显眼 endpoint，例如 backgrounds、themes、secrets、tokenizers、worldinfo、extensions。

应对：

- 先做 API 契约扫描。
- 运行 WebView 时记录所有 404/501 endpoint。
- 对非 MVP 功能 patch 隐藏入口或返回稳定空数据。

### 前端默认设置触发非 MVP provider

如果默认设置仍指向其他 provider，前端可能请求未实现 API。

应对：

- 转换阶段注入 Android 默认 settings。
- 默认 `main_api = openai`。
- 默认 `chat_completion_source = openai` 或 custom OpenAI-compatible。
- 新安装默认关闭 streaming，但保留并应用 preset 的 `stream_openai` 值。

### OpenAI streaming 的 provider 差异与断连释放

不同 OpenAI-compatible provider 可能返回标准 SSE、未声明 MIME 的 SSE，或在收到 `stream=true` 时仍返回普通 JSON；WebView 停止生成和客户端断连还必须释放上游连接。

应对：

- 标准及未声明 JSON 的响应走透明字节流，不在 Kotlin 中统一改写 provider payload。
- 明确 JSON 响应使用有大小上限的 SSE framing fallback，并追加 `[DONE]`。
- 流式 InputStream 关闭时关闭 response 并取消 OkHttp call，覆盖停止生成、页面离开、断连和 socket 写失败路径。
- 流式 diagnostics 只记录脱敏元数据，不复制正文或认证信息。

### 角色卡格式复杂

SillyTavern 支持 PNG 角色卡、metadata、导入导出、头像处理、缩略图。

应对：

- MVP 先支持 JSON 角色数据和默认头像。
- 完整 PNG/JSON 角色卡导入导出和 PNG metadata 解析放后续阶段；PNG/JPEG/WebP 仅作为头像媒体。
- 如果官方 UI 启动必须读取头像资源，提供占位图 endpoint。

### Patch 维护成本

允许 patch 会降低兼容成本，但可能逐渐变成 fork。

应对：

- patch queue 分类和说明强制执行。
- CI 每次 upstream 更新验证 patch。
- 每个 patch 都要证明不能由原生兼容层解决。

### App 私有目录不可见

用户不能直接用普通文件管理器查看数据。

应对：

- MVP 接受该取舍。
- 后续通过 SillyTavern UI 和 SAF 做导入导出。

### 本地重型模型不适合直接转换

SillyTavern 的 vectors、图片、语音等能力可能依赖 Transformers、WebLLM、Ollama、Python、native addon 或大模型文件。技术上可以使用 ONNX Runtime、TensorFlow Lite、llama.cpp 等重新实现，但会引入模型下载、数百 MB 体积、ABI/native 库、内存和机型兼容问题。

应对：

- 主体范围只实现数据、索引、文件和 UI/API 协议，不捆绑本地模型。
- embedding、图片、TTS、STT 等优先接入远程 HTTP provider。
- 任何本地模型能力必须单独立项，不得作为“转换 upstream”顺带加入。

## 决策记录

| 日期 | 决策 | 原因 |
|------|------|------|
| 2026-07-09 | 暂停 Node runtime + WebView 容器路线 | 用户目标是 APK 无 Node，只做转换和原生适配 |
| 2026-07-09 | 保留官方 SillyTavern Web UI | 避免完整重写前端，保留 upstream UI 价值 |
| 2026-07-09 | 第一版只支持 OpenAI-compatible provider | 覆盖 OpenAI、OpenRouter、DeepSeek 和兼容服务，MVP 价值最高 |
| 2026-07-09 | 推荐 Kotlin 本地 HTTP 兼容层 | 最小化前端 patch，匹配 SillyTavern 现有 `/api/...` 模型 |
| 2026-07-09 | 第一版默认关闭 streaming | 降低 HTTP server 和 SSE 兼容复杂度 |
| 2026-07-12 | 项目主体完成标准改为官方单用户功能基本全量 | 基础 MVP 已可运行，但可见 UI 与原生兼容层仍不一致 |
| 2026-07-12 | 本地重型模型和任意 Node 扩展不属于主体范围 | 避免把 APK 变成通用模型运行平台或新的运行时容器 |
| 2026-07-12 | 远程 embedding、图片、TTS、STT 按可选能力接入 | 保留功能扩展空间，同时控制 APK 体积和设备兼容风险 |
| 2026-07-12 | 旧数据迁移降级为主体完成后的可选项 | 当前优先完成新架构功能与发布链路，0.3.0 按全新安装交付 |
| 2026-07-12 | 角色卡按官方 UI 实现 PNG/JSON 导入导出，WebP 只作为头像媒体 | 当前 upstream 导出菜单只有 PNG/JSON，PNG metadata 使用 `ccv3`/`chara`；不发明非标准 WebP 角色卡 metadata |
| 2026-07-13 | 构建期 API scanner 改用 Acorn + parse5 | 自研 JavaScript/HTML lexer 连续审查仍暴露 regex、template、block、object spread 和 HTML attribute 语义缺口；两者只在构建期使用，不进入 APK |
| 2026-07-15 | 普通业务数据导入导出保留在主体范围，完整应用备份恢复与 Data Maid 推迟为主体完成后的可选项目 | 角色、聊天和 World Info 需要直接面向用户的 SAF 互操作；整应用 ZIP 恢复涉及 manifest、事务、回滚和删除风险，不应阻断当前核心功能与发布收口 |
| 2026-07-21 | OpenAI-compatible 支持 preset 可选 streaming/non-streaming | 消除前端 SSE 状态与原生 JSON 响应不一致导致的空消息，同时保留新安装默认关闭的保守行为 |
| 2026-07-21 | 流式正常路径采用透明 SSE，明确 JSON 响应使用 framing fallback | 复用 SillyTavern 前端解析语义，兼容忽略 `stream=true` 的 custom provider，避免在 Kotlin 中重写厂商 payload |
| 2026-07-21 | 断连取消上游请求，streaming diagnostics 只记录脱敏元数据 | 防止 foreground service 遗留请求，并确保 API key、prompt、SSE chunk 和生成正文不进入日志 |
| 2026-07-30 | 远程 Embedding 与本地 SQLite Vector Storage/RAG 纳入 0.3.x | 不在 APK 中打包模型或重量级向量库；远程生成向量，本地保存可重建派生索引 |

## 已确认边界

1. 基础对话正式支持 OpenAI-compatible preset 可选 streaming/non-streaming；正常 SSE 透明转发，明确 JSON 响应使用 framing fallback。
2. 项目主体补齐官方单用户核心能力，不追求全部 Node 服务端 endpoint。
3. 本地重型模型、依赖 Node 服务端的扩展、多用户和远程访问明确排除；client-only 扩展按 capability contract 有限支持。
4. 远程 embedding、图片、TTS、STT 等使用 capability gate，可在主体功能稳定后增量接入。
5. 旧数据迁移不阻断主体完成，若未来开发则单独设计。
6. 完整应用数据备份恢复与 Data Maid 不阻断主体完成，若未来开发则与旧数据迁移分别立项。

## 后续开发

0.3.x 主体、发布链路、扩展事务恢复和 Vector/RAG 已完成。当前没有跨功能的活动实施计划；完整应用备份恢复、远程 TTS/STT/Caption 或其他新增能力必须分别建立设计和实施计划。有效文档入口统一维护在 `docs/README.md`，已完成的过程文档由 Git 历史追溯。
