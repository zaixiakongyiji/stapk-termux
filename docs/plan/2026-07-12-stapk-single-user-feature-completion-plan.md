# stAPK 官方单用户功能补齐实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有无 Node MVP 基础上，把 SillyTavern 官方单用户核心 UI 对应的 Persona、角色卡、群组、聊天、World Info、背景、附件、Tokenizer、诊断和普通业务数据导入导出能力补齐为可发布的原生 Android 应用，同时保持 APK 运行时没有 Node.js。

**Architecture:** 构建期继续从固定 SillyTavern upstream ref 生成并 patch 官方 Web UI；运行期由 `NativeHttpService` 在 `127.0.0.1` 随机端口提供静态资源和 Kotlin 原生 API。先把 API contract 扩展为“UI action + endpoint + capability”严格契约，再建立安全文件、路由、multipart、流式文件响应和 SAF 导出基础设施，随后按能力域增加独立 controller，最后用 capability patch queue 重新开放已实现 UI 并隐藏明确排除项。

**Tech Stack:** Node.js 20+ 仅用于构建期 transform/contract；Acorn 8.17.0 用于构建期 JavaScript AST 扫描；parse5 7.3.0 用于构建期 HTML inline script 提取；Android Kotlin/JVM 17；NanoHTTPD 2.3.1；Gson 2.14.0；OkHttp 4.12.0；JTokkit 1.1.0；Android WebView；Storage Access Framework；JUnit 4.13.2；Node.js built-in test runner；GitHub Actions。

## Global Constraints

- 所有解释、计划、注释和文档使用中文；代码标识符、API endpoint、JSON key 和路径保持英文。
- APK 运行时不得包含或启动 Node.js、npm、`node_modules/`、Python、Shell、Ollama、`server.js`、runtime ZIP 或旧 `payload.tgz`。
- 只修改 stAPK 自身代码、构建期 patch queue 和生成产物，不直接修改 upstream checkout；每个 upstream 变更必须能由 patch queue 重放。
- 主体正式支持 OpenAI-compatible 非 streaming 对话；其他远程 AI 能力只允许以 `external_optional` capability 接入，不阻断主体完成。
- 不打包本地 embedding、图片生成、Whisper、TTS 或其他重型模型；不实现第三方 extension marketplace、任意服务端扩展、多用户和远程访问。
- 所有普通用户可见、可点击的核心动作必须映射到已实现 endpoint；明确排除的入口必须隐藏或显示“需要外部服务”，不得在正常路径返回 404/501。
- 本地 HTTP server 只绑定 `127.0.0.1`，端口由系统随机分配；不新增局域网监听配置。
- 文件写入只允许进入 app 私有目录或用户通过 SAF 明确授权的 URI；所有文件名、相对路径和 ZIP entry 必须经过路径穿越校验。
- JSON 数据写入必须原子替换并保留未知字段；损坏文件必须移入隔离目录并写诊断记录，不得静默覆盖。
- secrets 与 settings、日志、报告和导出元数据分离；日志默认不得记录完整 prompt、response 或 API key。
- 单文件上传和前端生成导出暂存上限 32 MiB。完整数据 ZIP 备份恢复已推迟为主体完成后的可选项目，其容量和 ZIP 安全限制不得提前混入当前主体实现。
- 角色卡兼容官方 UI 实际支持的 PNG/JSON 导入导出；WebP 作为角色/Persona/背景头像媒体支持。不得发明没有规范依据的 WebP 角色卡元数据格式。
- 旧 0.2.x 数据迁移不属于本计划；不得创建空迁移 manager，不得读取、删除或改写 `filesDir/SillyTavern/`。
- 0.3.0 发布说明必须明确建议全新安装，不承诺从 0.2.x 原地升级。
- 禁止主动执行 git commit/push；每个任务只提供建议提交信息，由用户手动触发。

---

## 基线与完成门槛

当前基线已经具备 no-node transform、官方 Web UI、loopback HTTP server、settings、角色基础 CRUD、普通聊天基础 CRUD、OpenAI-compatible 非 streaming 对话和 MVP patch queue。本计划不重做这些能力。

主体完成必须同时满足：

1. `api-contract.json` 中所有 `exposure=visible` endpoint 均为 `implemented` 或 `external_optional`，且 `external_optional` 在未配置时有明确 UI 状态。
2. Persona、角色、群组、普通/群组聊天、World Info、背景、附件、Tokenizer、settings/presets/themes、诊断和 SAF 数据互操作均有 JVM 或 Node fixture 测试。
3. `npm run build:no-node-apk -- --variant debug` 能从 upstream ref 一键生成、严格验证、测试并构建 APK。
4. 干净安装后能从官方 UI 完成能力矩阵中的真实操作，重启后数据仍存在，设备上没有 Node 进程。
5. 发布工作流上传 APK、checksum、API contract、capability contract、Web manifest 和 transform report。

---

## 文件结构与职责

### 构建期契约

- `transform/no-node/capabilities.json`：声明能力 ID、范围、默认状态和 endpoint 归属。
- `transform/schemas/capability-contract.schema.json`：校验能力契约生成结果。
- `scripts/stapk-scan-web-contract.mjs`：提取静态 fetch、API 字符串常量和模板 URL，生成 endpoint 与源位置。
- `scripts/stapk-verify-capability-contract.mjs`：阻断可见 `needs_review`、无归属 endpoint 和错误 capability 状态。
- `test/no-node/capability-contract.test.mjs`：覆盖动态 URL、tokenizer 常量和可见动作严格门禁。

### Android 通用基础设施

- `NativeRequest.kt`：统一 JSON、query、form 和 multipart upload 请求模型。
- `NativeRouter.kt`：按 method/path 注册 controller，避免继续扩张 `NativeHttpServer.when`。
- `AtomicFileStore.kt`：临时文件、`fsync`、原子替换和损坏文件隔离。
- `SafePath.kt`：安全文件名、canonical containment 和 ZIP entry 校验。
- `ExportStore.kt`：管理短生命周期导出文件和单次下载 token。
- `HttpResponse.kt`：支持 headers、byte array 和 file stream 三种响应体。

### 单用户能力 controller

- `PersonaController.kt`：Persona 图片和当前用户头像 endpoint；Persona 元数据继续由 settings 未知字段保留。
- `PresetController.kt`、`ThemeController.kt`：OpenAI-compatible presets、themes 和 settings snapshots。
- `CharacterCardCodec.kt`：PNG `chara`/`ccv3` chunk 与 JSON 角色卡编解码。
- `GroupController.kt`、`GroupChatController.kt`：群组和群聊持久化。
- `ChatBackupController.kt`：recent、重命名、导入导出和聊天备份。
- `WorldInfoController.kt`：World Info CRUD、导入导出和绑定数据持久化。
- `BackgroundController.kt`、`FileController.kt`、`ImageMetadataController.kt`：背景、附件、本地图片与元数据。
- `TokenizerController.kt`：OpenAI-compatible tokenizer encode/decode/count。
- `DataArchiveController.kt`：仅作为主体完成后的可选设计占位说明；当前计划不创建该文件，也不注册完整备份恢复路由。

### Android UI bridge

- `StapkFileBridge.kt`：JavaScript 到 Android 的导出 token 请求桥。
- `SafExportCoordinator.kt`：`ACTION_CREATE_DOCUMENT`、流式复制和结果通知。
- `MainActivity.kt`：注册可信来源 JavaScript interface 和 Activity Result 流程。

---

### Task 0：冻结 capability contract 和纠正文档边界

**目的：** 先让“什么必须实现、什么外部可选、什么明确排除”成为机器可验证契约，避免继续依靠 CSS 隐藏列表判断完成度。

**Files:**
- Create: `transform/no-node/capabilities.json`
- Create: `transform/schemas/capability-contract.schema.json`
- Create: `scripts/stapk-verify-capability-contract.mjs`
- Create: `test/no-node/capability-contract.test.mjs`
- Modify: `scripts/stapk-scan-web-contract.mjs`
- Modify: `scripts/stapk-verify-no-node-transform.mjs`
- Modify: `transform/schemas/api-contract.schema.json`
- Modify: `transform/no-node/mvp-api-allowlist.json`
- Modify: `package.json`
- Modify: `package-lock.json`
- Modify: `docs/superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md`

**Interfaces:**
- Produces: `scanWebContract({ webRoot, allowlistFile, capabilityFile, upstream })`。
- Produces: `verifyCapabilityContract({ apiContract, capabilities })`。
- Produces: endpoint fields `capability`, `exposure`, `sourceLocations`, `dynamic`。
- Status enum: `implemented | external_optional | unsupported_hidden | needs_review`。
- Capability enum: `core.settings | core.personas | core.characters | core.groups | core.chats | core.world_info | core.backgrounds | core.files | core.tokenizers | core.data_management | remote.embeddings | remote.image | remote.tts | remote.stt | remote.caption | remote.translation | excluded.extensions | excluded.local_models | excluded.multiuser`。

- [x] **Step 1：写失败测试，证明 scanner 能发现常量和模板 endpoint**

在 `capability-contract.test.mjs` 构造包含以下源码的临时 Web root：

```js
const endpoints = {
  encode: '/api/tokenizers/openai/encode',
  decode: '/api/tokenizers/openai/decode',
};
const countUrl = `/api/tokenizers/openai/count?model=${model}`;
await fetch(endpoints.encode, { method: 'POST', body: '{}' });
```

断言输出包含三个规范化 endpoint，`count` 的 `dynamic=true`，且 query 被移除但源表达式被保留。

- [x] **Step 2：运行测试并确认失败原因**

Run: `node --test test/no-node/capability-contract.test.mjs`

Expected: FAIL，当前 scanner 只能识别直接传给 `fetch()` 的字符串字面量。

- [x] **Step 3：扩展 scanner 数据模型**

实现以下导出函数并保持现有 `extractFetchRequests()` 测试通过：

```js
export function extractApiLiterals(source, sourceFile) {
  // 返回 { path, expression, line, dynamic }[]；只接受 /api/ 开头的字符串或模板字面量。
}

export function mergeEndpointEvidence(fetchRequests, apiLiterals) {
  // 直接 fetch 保留真实 method；仅由常量发现的 endpoint 默认 method=POST 并标记 inferredMethod=true。
}
```

`sourceLocations` 固定格式：

```json
[
  {
    "file": "scripts/tokenizers.js",
    "line": 72,
    "expression": "/api/tokenizers/openai/encode"
  }
]
```

2026-07-13 架构决策：scanner 使用 `acorn@8.17.0` 解析 JavaScript AST，不再维护自研 JavaScript lexer；HTML 使用 `parse5@7.3.0` 提取可执行 inline `<script>`，不使用正则模拟 HTML tokenizer。选择 parse5 7.3.0 是为了保持项目既有 `Node >=20.0.0` 构建合同，避免 parse5 8 的传递依赖要求 Node 20.19。`.js/.mjs/.cjs` 按 `ecmaVersion=latest` 解析，按扩展名和 parse fallback 选择 `module`/`script`；HTML script 保留原始行号偏移并跳过 comment、template、`src`、JSON/importmap/inert script。AST walker 提取 string literal、template literal 和 `fetch()` CallExpression；对象 method 属性按 JavaScript 最后写入语义处理，后置 spread 或动态 key 使 method 退回未知推断。Acorn 和 parse5 只属于构建期 npm 依赖，不复制到 Android assets，不影响 APK 运行时无 Node 约束。

- [x] **Step 4：写 capability 配置和 schema**

`capabilities.json` 必须逐项声明 `id`、`kind`、`defaultStatus`、`endpointPrefixes` 和 `uiPolicy`。核心能力使用 `kind=core`、`uiPolicy=visible_when_implemented`；远程增强使用 `kind=external_optional`、`uiPolicy=visible_when_configured`；排除项使用 `kind=excluded`、`uiPolicy=hidden`。

Schema 要求 endpoint 只能归属于一个 capability；`external_optional` 不能用于 `core.*`；`unsupported_hidden` 不能用于 `uiPolicy=visible_when_implemented`。

- [x] **Step 5：实现严格验证器**

验证器必须返回：

```js
{
  ok: boolean,
  errors: string[],
  visibleNeedsReview: string[],
  unassignedEndpoints: string[],
  summaryByCapability: Record<string, Record<string, number>>
}
```

以下情况必须失败：可见 `needs_review`、endpoint 无 capability、同一 endpoint 多重归属、排除项被标记 implemented、核心项被标记 external_optional。

- [x] **Step 6：接入 transform 严格门禁**

在 `package.json` 增加：

```json
"verify:no-node-capabilities": "node scripts/stapk-verify-capability-contract.mjs --contract build/no-node-payload/api-contract.json --capabilities transform/no-node/capabilities.json"
```

`transform:no-node:verify` 在资产检查后调用 capability verifier。开发阶段允许通过 `--allow-visible-needs-review` 输出报告；Task 11 前 CI 和 Release 不得使用该参数。

- [x] **Step 7：纠正角色卡格式表述并记录依据**

把设计文档中的“PNG/WEBP/JSON 角色卡导入导出”改为“PNG/JSON 角色卡导入导出，PNG/JPEG/WebP 作为头像媒体导入”。记录官方 Web UI 的导出菜单只有 PNG/JSON，PNG 卡读取 `ccv3` 优先于 `chara`，不定义非标准 WebP metadata。

- [x] **Step 8：验证**

Run:

```powershell
npm run test:no-node
npm run transform:no-node
npm run verify:no-node-capabilities -- --allow-visible-needs-review
```

Expected: Node tests 全部通过；报告能发现 tokenizer 动态 endpoint；当前尚未补齐的核心 endpoint 被列为 `visibleNeedsReview`，但开发参数允许生成报告。

**建议提交（由用户手动触发）：** `feat: 建立单用户 capability 严格契约`

---

### Task 1：建立安全路由、文件和响应基础设施

**目的：** 在增加文件型 endpoint 前解决巨型路由、路径穿越、非原子写入、multipart 和大文件响应问题。

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeRequest.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeRouter.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/AtomicFileStore.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/SafePath.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExportStore.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/NativeRouterTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/AtomicFileStoreTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/SafePathTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ExportStoreTest.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/HttpResponse.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`

**Interfaces:**

```kotlin
data class UploadedFile(val fieldName: String, val originalName: String, val mimeType: String, val tempFile: File)
data class NativeRequest(
    val method: String,
    val path: String,
    val query: Map<String, List<String>>,
    val form: Map<String, List<String>>,
    val bodyText: String,
    val uploads: Map<String, UploadedFile>
)
fun interface NativeRouteHandler { fun handle(request: NativeRequest): HttpResponse }
class NativeRouter {
    fun post(path: String, handler: NativeRouteHandler)
    fun get(path: String, handler: NativeRouteHandler)
    fun dispatch(request: NativeRequest): HttpResponse?
}
```

`HttpResponse` 新增 `headers: Map<String,String>` 和 `bodyFile: File?`；三个 body 字段必须至多一个非空。

- [x] **Step 1：写路由和 API fallback 失败测试**

断言重复注册抛出 `IllegalArgumentException`；method 不匹配返回 null；未知 `/api/...` 由 server 返回 JSON `404 {"error":"endpoint_not_found"}`，而不是交给静态文件 controller。

- [x] **Step 2：实现 `NativeRouter` 并迁移现有 endpoint**

把 `NativeHttpServer` 现有 settings、secrets、OpenAI、characters 和 chats 路由注册到 `registerRoutes(router)`。静态 `/characters/`、`/thumbnail` 和 Web assets 仍由明确 fallback 处理。

- [x] **Step 3：写安全路径失败测试**

覆盖 `../x`、`..\\x`、绝对路径、NUL、控制字符、URL 编码后的分隔符、canonical 逃逸、ZIP entry `a/../../x`；覆盖合法中文显示名经过清理后得到非空安全文件名。

- [x] **Step 4：实现 `SafePath`**

```kotlin
object SafePath {
    fun fileName(input: String, fallback: String = "file"): String
    fun child(root: File, relative: String): File
    fun zipEntry(name: String): String
}
```

`child()` 必须比较 canonical path；`fileName()` 去除分隔符和控制字符并限制为 120 个 Unicode code point；`zipEntry()` 统一 `/` 后拒绝空段、`.`、`..` 和绝对路径。

- [x] **Step 5：写原子存储失败测试**

覆盖写入成功、替换旧文件、序列化异常不破坏旧文件、损坏 JSON 移入 `user_data/quarantine/<timestamp>/`、并发写最终文件始终可解析。

- [x] **Step 6：实现 `AtomicFileStore`**

```kotlin
class AtomicFileStore(private val quarantineDir: File) {
    fun writeText(target: File, value: String)
    fun writeBytes(target: File, value: ByteArray)
    fun readJsonObject(target: File): JsonObject?
    fun quarantine(target: File, reason: String): File
}
```

写入流程固定为同目录 `.tmp`、`FileOutputStream.fd.sync()`、`Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`，不支持 `ATOMIC_MOVE` 时退化为同目录 replace。

- [x] **Step 7：实现 `ExportStore` 和 file response**

```kotlin
data class ExportTicket(val token: String, val file: File, val fileName: String, val mimeType: String, val expiresAt: Long)
class ExportStore(private val exportsDir: File, private val clock: () -> Long = System::currentTimeMillis) {
    fun create(fileName: String, mimeType: String, writer: (File) -> Unit): ExportTicket
    fun consume(token: String): ExportTicket?
    fun cleanupExpired()
}
```

token 使用 32-byte `SecureRandom` URL-safe Base64；有效期 15 分钟；`consume()` 单次有效。`HttpResponse.file()` 必须添加 `Content-Length`、RFC 5987 `Content-Disposition` 和 `X-stAPK-Export-Token`。

- [x] **Step 8：实现 multipart 请求转换和上传限制**

`NativeHttpServer.parseRequest()` 将 NanoHTTPD 临时文件、原始文件名、字段名和 content type 转为 `UploadedFile`；任何单文件超过 32 MiB 返回 `413 {"error":"upload_too_large"}`。临时文件只在请求生命周期读取，不移动到最终目录。

- [x] **Step 9：验证**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.NativeRouterTest" --tests "com.stapk.mobile.nativeadapter.AtomicFileStoreTest" --tests "com.stapk.mobile.nativeadapter.SafePathTest" --tests "com.stapk.mobile.nativeadapter.ExportStoreTest"
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`，现有 46 个基线测试无回归。

**建议提交（由用户手动触发）：** `refactor: 建立原生适配安全路由和文件基础设施`

---

### Task 2：补齐 settings、Persona、themes、presets 和 snapshots

**目的：** 让官方设置和 Persona 管理 UI 不再依赖被隐藏入口，并保证未知字段、头像和快照持久化。

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/PersonaController.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/PresetController.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ThemeController.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/UiStateController.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/PersonaControllerTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/PresetControllerTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ThemeControllerTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/UiStateControllerTest.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/SettingsController.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/SettingsControllerTest.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeAdapterPaths.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`

**Interfaces:**
- Paths: `personasDir`, `presetsDir`, `themesDir`, `quickRepliesDir`, `movingUiDir`, `settingsBackupsDir`, `quarantineDir`。
- Routes: `/api/avatars/get|upload|delete`、`/api/users/change-avatar`、`/api/users/reset-settings`、`/api/themes/save|delete`、`/api/presets/save|delete|restore`、`/api/settings/get-snapshots|load-snapshot|make-snapshot|restore-snapshot`、`/api/quick-replies/save|delete`、`/api/moving-ui/save`。
- Static: `/User Avatars/<file>`。

- [x] **Step 1：写 Persona fixture 测试**

覆盖空列表、上传 PNG/JPEG/WebP、同名覆盖、删除、非法 MIME、路径穿越和静态读取。断言 `/api/avatars/get` 返回按文件名排序的字符串数组；上传返回规范化文件名；删除成功返回 `{}`。

- [x] **Step 2：实现 Persona 图片存储**

只通过 magic bytes 接受 PNG、JPEG、WebP，不信任扩展名。Persona 名称、描述、position、lorebook 绑定和角色绑定继续保存在 `settings.json` 的官方字段中，`SettingsController.saveSettings()` 必须 deep-copy 未识别字段。

- [x] **Step 3：写 preset/theme fixture 测试**

Preset body 固定为 `{name, preset, apiId}`；只允许 `apiId=openai`。Theme body 固定为包含 `name` 的任意 JSON object。保存后 `/api/settings/get` 必须返回对应 `openai_setting_names/openai_settings/themes` 数据，删除后立即消失。

- [x] **Step 4：实现 preset 和 theme controller**

每项独立 JSON 文件，文件名由 `SafePath.fileName()` 生成；内容保留未知字段；同名保存原子覆盖；restore 从 transform 同步的默认资产读取，只恢复指定 preset，不改当前 secrets。

- [x] **Step 5：写 snapshot fixture 测试**

覆盖创建、列表、加载但不应用、恢复并覆盖 settings、删除损坏 snapshot 到 quarantine。Snapshot 文件名固定为 `settings_<yyyyMMdd-HHmmss-SSS>.json`，列表返回 `{name, date, size}` 数组。

- [x] **Step 6：实现 settings snapshot 和 reset**

`load-snapshot` 返回 snapshot JSON；`restore-snapshot` 原子替换当前 settings；`make-snapshot` 返回 `{name}`；`reset-settings` 恢复 no-node 默认 settings，但保留 `providerConfigFile` 和 secrets。

- [x] **Step 7：实现 Quick Replies 和 moving UI 存储**

Quick Reply body 为包含 `name` 的完整 set object，保存到 `quick_replies/<safeName>.json`；delete 删除同名 set。Moving UI body 为包含 `name` 的完整 layout object，保存到 `moving_ui/<safeName>.json`；这些文件由 `/api/settings/get` 聚合为官方字段，保存时保留未知属性。

- [x] **Step 8：注册路由并更新 capability allowlist**

把本 Task 的 endpoint 标记为 `core.settings` 或 `core.personas/implemented`。删除 patch 中对 Persona 主按钮、Persona lorebook 按钮、Quick Replies、themes/presets 正常入口的隐藏规则。

- [x] **Step 9：验证**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.PersonaControllerTest" --tests "com.stapk.mobile.nativeadapter.PresetControllerTest" --tests "com.stapk.mobile.nativeadapter.ThemeControllerTest" --tests "com.stapk.mobile.nativeadapter.UiStateControllerTest" --tests "com.stapk.mobile.nativeadapter.SettingsControllerTest"
Set-Location ..
npm run test:no-node
```

Expected: 对应 JVM 测试和 Node patch 测试通过。

**建议提交（由用户手动触发）：** `feat: 补齐 Persona 和设置管理能力`

---

### Task 3：补齐 PNG/JSON 角色卡、头像和高级角色操作

**目的：** 完成官方角色管理 UI 中的导入、导出、复制、重命名、属性合并和头像编辑。

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/CharacterCardCodec.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/AvatarImageNormalizer.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/CharacterCardCodecTest.kt`
- Create: `mobile/app/src/test/resources/fixtures/character-card-v2.json`
- Create: `mobile/app/src/test/resources/fixtures/character-card-v3.json`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/CharacterController.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/CharacterControllerTest.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`

**Interfaces:**

```kotlin
data class DecodedCharacterCard(val json: JsonObject, val avatarBytes: ByteArray?, val sourceFormat: String)
class CharacterCardCodec {
    fun decodeJson(bytes: ByteArray): DecodedCharacterCard
    fun decodePng(bytes: ByteArray): DecodedCharacterCard
    fun encodePng(baseImage: ByteArray, card: JsonObject): ByteArray
}
fun interface AvatarImageNormalizer {
    fun toPng(source: ByteArray): ByteArray
}
```

Routes: `/api/characters/import|export|duplicate|rename|merge-attributes|edit-avatar`。

- [x] **Step 1：写 PNG codec 失败测试**

测试生成最小 1x1 PNG，写入 `chara` 与 `ccv3` tEXt chunk，断言 `ccv3` 优先；CRC 错误、缺少 metadata、非法 Base64 和超过 8 MiB metadata 必须返回明确失败。

- [x] **Step 2：实现 PNG chunk 编解码**

校验 PNG signature；按 big-endian 读取 length/type/data/crc；使用 `CRC32` 校验；移除已有 `chara`/`ccv3` 后在 `IEND` 前写入两个 tEXt chunk。`chara` 保存原始兼容 JSON，`ccv3` 保存将 `spec/spec_version` 更新为 v3 的 deep copy。

- [x] **Step 3：写 JSON 角色卡兼容测试**

覆盖 V1 扁平字段、V2、V3、未知字段和 embedded `character_book`。标准化只能补缺失字段，不得删除未知字段；导入再导出后 unknown extension 必须完全保留。

- [x] **Step 4：实现角色 import/export**

`import` 接受 multipart field `avatar`，支持 `.png` 和 `.json`；`.json` 使用默认头像。`export` 接受 `{format:"png|json", avatar_url}`；PNG 从当前头像或默认头像写 metadata；JSON 返回 UTF-8。两个响应都通过 `ExportStore` 产生 ticket。

- [x] **Step 5：实现头像编辑**

`edit-avatar` 接受 PNG/JPEG/WebP，经 `BitmapFactory` 解码并以 PNG 重新编码到 `<stem>.png`；公开 avatar identity 始终保持 `<stem>.png`。Controller 通过注入的 `AvatarImageNormalizer` 便于 JVM 测试，Android 默认实现负责真实图片转换；解码失败返回 `400 {"error":"invalid_avatar_image"}`，不得覆盖旧头像。

- [x] **Step 6：实现 duplicate/rename/merge-attributes**

复制必须生成唯一 stem 并复制头像但不复制聊天；重命名必须原子移动角色 JSON、头像和角色 chat directory；merge 只修改请求提供字段，支持单角色 `{avatar, data}` 和批量 `{avatars, data}`，保留其他字段。

- [x] **Step 7：注册路由并更新 contract**

角色能力下所有官方可见 endpoint 标记 `core.characters/implemented`。外部 URL 导入仍隐藏，因为它需要任意远程下载和额外安全策略。

- [x] **Step 8：验证**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.CharacterCardCodecTest" --tests "com.stapk.mobile.nativeadapter.CharacterControllerTest"
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```

Expected: codec、controller 和完整 JVM 测试通过。

**建议提交（由用户手动触发）：** `feat: 补齐角色卡导入导出和高级操作`

---

### Task 4：实现群组和群聊持久化

**目的：** 恢复官方角色列表中的建群、成员管理、群组编辑和群聊切换能力。

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/GroupController.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/GroupChatController.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/GroupControllerTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/GroupChatControllerTest.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeAdapterPaths.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`

**Interfaces:**
- Paths: `groupsDir`, `groupChatsDir`。
- Group routes: `/api/groups/all|create|edit|delete`。
- Group chat routes: `/api/chats/group/get|save|delete|info|import`。
- Group file: `groups/<id>.json`；group chat: `group_chats/<id>.jsonl`。

- [x] **Step 1：写 group fixture 测试**

使用包含 `id,name,members,disabled_members,activation_strategy,allow_self_responses,fav,chat_id,past_metadata` 的 fixture。断言 create 返回完整 group object；edit 保留未知字段；delete 可选择删除关联群聊。

- [x] **Step 2：实现 `GroupController`**

ID 使用 `System.currentTimeMillis()` 加 6-byte random hex，避免依赖顺序自增。成员只保存角色 avatar identity；保存前过滤不存在角色并保留顺序。`all` 按 `id` 稳定排序。

- [x] **Step 3：写 group chat fixture 测试**

覆盖 save/get、metadata 首行、消息追加后的完整覆盖、info 摘要、delete 和 JSONL import。非法 JSONL 必须放入 quarantine，现有群聊不得被覆盖。

- [x] **Step 4：实现 `GroupChatController`**

`save` 接受 `{id, chat}`，将 `chat` 中每个 object 单行 Gson 序列化；`get` 返回 object array；`info` 返回 `file_name,file_size,chat_items,last_mes,chat_metadata`；import 只接受 `.jsonl`。

- [x] **Step 5：注册动态 scanner 漏掉的 save endpoint**

确保 `/api/chats/group/save` 由 Task 0 scanner 发现并标记 `core.groups/implemented`，不能依赖手写漏项。

- [x] **Step 6：恢复群组 UI 并验证 patch**

删除 MVP patch 中“Create Group”、group list 和 group lorebook 的隐藏规则；group lorebook 在 Task 6 完成前保持 capability 控制，不能提前显示可点击入口。

- [x] **Step 7：验证**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.GroupControllerTest" --tests "com.stapk.mobile.nativeadapter.GroupChatControllerTest"
Set-Location ..
npm run test:no-node
```

Expected: 群组 controller 测试和 patch 测试通过。

**建议提交（由用户手动触发）：** `feat: 添加群组和群聊原生适配`

---

### Task 5：扩展聊天 recent、重命名、导入导出和备份

**目的：** 补齐欢迎页最近聊天、聊天文件管理、JSONL/TXT 导出和聊天备份恢复。

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ChatBackupController.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/StatsController.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ChatBackupControllerTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/StatsControllerTest.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ChatController.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ChatControllerTest.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeAdapterPaths.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`

**Interfaces:**
- Routes: `/api/chats/recent|rename|import|export`、`/api/backups/chat/get|download|delete`、`/api/stats/get|recreate|update`。
- Paths: `chatBackupsDir`, `statsFile`。

- [x] **Step 1：写 recent 和 rename 测试**

创建两个角色聊天和一个群聊，设置确定的 `lastModified`，断言 recent 合并后按时间降序，返回 `avatar_url/group_id/file_name/mes/last_mes`。Rename 必须拒绝跨角色移动和已存在目标。

- [x] **Step 2：实现 recent 和 rename**

只扫描 `chats/<character>/` 和 `group_chats/`；不跟随 symlink；每类最多读取最近 200 个文件；损坏文件跳过并记录诊断。

- [x] **Step 3：写 import/export 测试**

JSONL 导入验证每个非空行都是 object；TXT 导出格式固定为 `name: message`，system/narrator 仍保留；JSONL 导出保持原始字段。导出响应必须包含 export ticket。

- [x] **Step 4：实现 import/export**

普通聊天 import 需要 `avatar_url`，群聊 import 由 Task 4 controller 处理。导入目标冲突时追加 `-1`、`-2`；不覆盖现有文件。

- [x] **Step 5：写 chat backup 测试**

保存聊天前先生成 `.jsonl` 快照，文件名固定 `<chatStem>_<yyyyMMdd-HHmmss-SSS>.jsonl`；每个聊天最多保留 50 份。get 返回 `name,size,date`；download 生成 ticket；delete 只接受列表中真实名称。

- [x] **Step 6：实现 backup controller 并接入 save**

仅当目标聊天已存在且内容发生变化时生成 backup；首次保存不生成空备份。备份失败不得阻断聊天主写入，但必须记录脱敏诊断。

- [x] **Step 7：实现单用户统计**

`recreate` 扫描普通聊天并按角色统计 `total_gen_time,user_word_count,non_user_word_count,user_msg_count,non_user_msg_count,total_swipe_count,total_chat_size,date_last_chat,chat_size`；`update` 接受前端增量并原子合并到 `stats.json`；`get` 返回按 avatar stem keyed 的 object。损坏聊天跳过并记录文件名，不阻断其他统计。

- [x] **Step 8：恢复 welcome recent、stats 和聊天文件 UI**

移除欢迎页 recent chat 和角色 stats 的 MVP 屏蔽；保持真正依赖扩展或多用户的入口隐藏。

- [x] **Step 9：验证**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.ChatControllerTest" --tests "com.stapk.mobile.nativeadapter.ChatBackupControllerTest" --tests "com.stapk.mobile.nativeadapter.StatsControllerTest"
Set-Location ..
npm run test:no-node
```

Expected: recent/import/export/backup fixture 和 Node UI 测试通过。

**建议提交（由用户手动触发）：** `feat: 补齐聊天文件管理和备份`

---

### Task 6：实现 World Info CRUD、导入导出和绑定

**目的：** 恢复全局、角色、Persona、群聊 lorebook 的官方 UI；数据由原生层存储，prompt 组装继续由官方前端完成。

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/WorldInfoController.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/WorldInfoControllerTest.kt`
- Create: `mobile/app/src/test/resources/fixtures/world-info.json`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeAdapterPaths.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/OpenAiCompatibleControllerTest.kt`

**Interfaces:**
- Path: `worldInfoDir`。
- Routes: `/api/worldinfo/list|get|edit|delete|import`。
- Export: `get` 返回完整 JSON，官方前端负责生成下载；`import` 返回规范化 lorebook 名称。

- [x] **Step 1：写 World Info fixture 测试**

Fixture 覆盖 `entries` object map、`uid,key,keysecondary,content,comment,constant,selective,order,position,disable,excludeRecursion,preventRecursion,delay,probability,useProbability,depth,role,group,scanDepth,caseSensitive,matchWholeWords,useGroupScoring,automationId` 和未知字段。

- [x] **Step 2：实现 list/get/edit/delete**

内部文件为 `world_info/<safeName>.json`；list 返回不带扩展名的排序数组；edit body `{name, data}` 原子保存；delete 只删除目标文件，不自动改写角色/settings 绑定，前端下一次加载时负责清理失效选择。

- [x] **Step 3：实现 import 和格式兼容**

接受官方 World Info JSON、Character Book V2 和 Lorebook V3 `{spec:"lorebook_v3",data}`。只做结构转换，不丢 unknown extension；同名文件追加数字后缀。

- [x] **Step 4：验证生成请求中的 lorebook 内容**

在 `OpenAiCompatibleControllerTest` 构造官方前端已经组装好的 messages，包含 World Info system message，断言 controller 原样转发 message 顺序和 content，不由 Kotlin 再次注入，避免重复 prompt。

- [x] **Step 5：恢复所有 lorebook UI**

删除 `#WI-SP-button`、`#world_button`、`.chat_lorebook_button`、`#persona_lore_button`、角色 world selector 和 group lorebook 的隐藏规则。保留 vector/RAG UI 隐藏，因为它属于 `remote.embeddings`。

- [x] **Step 6：验证**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.WorldInfoControllerTest" --tests "com.stapk.mobile.nativeadapter.OpenAiCompatibleControllerTest"
Set-Location ..
npm run test:no-node
```

Expected: World Info 与 provider 请求测试通过，patch 测试确认 lorebook UI 已恢复。

**建议提交（由用户手动触发）：** `feat: 添加 World Info 原生适配`

---

### Task 7：实现背景、附件、本地图片和图像元数据

**目的：** 支持官方背景管理、聊天附件、角色图库和本地图片文件夹，不包含远程图片生成。

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/BackgroundController.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/FileController.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ImageMetadataController.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/SpriteController.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/BackgroundControllerTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/FileControllerTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ImageMetadataControllerTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/SpriteControllerTest.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeAdapterPaths.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/StaticAssetController.kt`

**Interfaces:**
- Paths: `backgroundsDir`, `uploadsDir`, `userImagesDir`, `imageMetadataFile`。
- Background routes: `/api/backgrounds/all|folders|upload|rename|delete`。
- File routes: `/api/files/sanitize-filename|upload|delete|verify`。
- Image routes: `/api/images/upload|list|folders|delete`。
- Metadata routes: `/api/image-metadata`、`/all`、`/cleanup`、`/folders/get|create|update|delete|assign|unassign|set-thumbnails`。
- Sprite routes: `GET /api/sprites/get`、`POST /api/sprites/upload|upload-zip|delete`。
- Static prefixes: `/backgrounds/`、`/user/images/`、`/files/`。

- [x] **Step 1：写背景 controller 测试**

覆盖 PNG/JPEG/WebP/GIF 上传、同名处理、列表、文件夹、重命名、删除、静态读取和 MIME。MP4/WebM 可以原样存储和播放，但不做格式转换；依赖 converter extension 的入口保持隐藏。

- [x] **Step 2：实现背景 controller 和静态目录映射**

静态读取必须复用 `SafePath.child()`；响应添加 `Cache-Control: no-store`，避免替换同名背景后 WebView 继续显示旧内容。

- [x] **Step 3：写附件 controller 测试**

`sanitize-filename` 返回 `{fileName}`；upload 接受 `{name,data}` Base64 和 multipart 两种官方路径，返回 `{path}`；verify 输入 URL 数组，返回存在/缺失结果；delete 只能删除 `/files/` 下文件。

- [x] **Step 4：实现附件 controller**

拒绝 HTML/SVG/JS 作为可执行内联资源；未知二进制统一 `application/octet-stream` 和 attachment disposition。文本附件最大 8 MiB，其他附件沿用 32 MiB 上限。

- [x] **Step 5：写 image metadata 测试**

覆盖 folder CRUD、批量 assign/unassign、thumbnail、按 path 查询、all、cleanup orphan。Metadata 文件为单个 JSON object，所有更新在 controller 内同步并原子保存。

- [x] **Step 6：实现 images 和 metadata controller**

Image 相对路径限定在 `user_images/`；folder ID 使用 random token；删除文件时同步移除 metadata；删除 virtual folder 只解除分配，不删除图片。

- [x] **Step 7：实现角色 sprites**

Sprites 保存到 `characters/<stem>/sprites/`；upload 接受 image field 和 `{name,label}`；get 返回 `{label,path}` 数组；delete 只删除指定 label。ZIP 上传应用全局 ZIP entry/总量限制，只提取 PNG/JPEG/WebP/GIF，entry 文件名作为 label，任何非法 entry 使整个导入失败且不写最终目录。

- [x] **Step 8：更新 capability 和 UI**

恢复背景、附件、角色图库和手工 expression sprites 的核心入口；自动表情分类属于远程 caption/classify 能力，`sd_*`、远程图片 provider 和视频转换 extension 继续标记 `remote.image` 或 `excluded.extensions`。

- [x] **Step 9：验证**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.BackgroundControllerTest" --tests "com.stapk.mobile.nativeadapter.FileControllerTest" --tests "com.stapk.mobile.nativeadapter.ImageMetadataControllerTest" --tests "com.stapk.mobile.nativeadapter.SpriteControllerTest"
Set-Location ..
npm run test:no-node
```

Expected: 文件 controller 与 patch 测试通过。

**建议提交（由用户手动触发）：** `feat: 添加背景附件和本地图片管理`

---

### Task 8：实现 OpenAI-compatible Tokenizer

**目的：** 让角色 token count、prompt token count 和 OpenAI-compatible count 使用真实 BPE，不使用字符数伪估算，也不打包神经网络推理模型。APK 只保留 tokenizer 运行必需的 `cl100k_base.tiktoken` 和 `o200k_base.tiktoken` BPE 词表。

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/TokenizerController.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/TokenizerControllerTest.kt`
- Modify: `mobile/app/build.gradle.kts`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`
- Modify: `patches/sillytavern-no-node/series`
- Create: `patches/sillytavern-no-node/0006-stapk-mobile-openai-tokenizer.patch`
- Modify: `test/no-node/no-node-transform.test.mjs`

**Interfaces:**
- Dependency: `implementation("com.knuddels:jtokkit:1.1.0")`。
- Routes: `/api/tokenizers/openai/encode|decode|count`、`/api/backends/chat-completions/bias`。
- Encoding mapping: GPT-4o、GPT-4.1、GPT-4.5、GPT-5、o1、o3、o4 名称使用 `O200K_BASE`；其余 OpenAI-compatible 模型默认 `CL100K_BASE`。

- [x] **Step 1：写固定向量失败测试**

断言 `cl100k_base("hello world") == [15339, 1917]`；encode 返回 `{ids,count,chunks}`；decode 返回 `{text}`；count 对 chat messages 使用 `3 tokens/message + encoded values + 1 token/name + 3 padding`。

- [x] **Step 2：添加依赖并实现 controller**

`EncodingRegistry` 和两个 `Encoding` 在 controller 构造时初始化并共享。Decode 拒绝负数和超过 `Int.MAX_VALUE` 的 token ID。异常返回 400 JSON，不返回静默空数组。

`bias` 接受 `[{text,value}]`，使用相同 model encoding，把每个 token ID 映射到 value；空 text 忽略，非法 value 返回 400。它必须与 tokenizer model mapping 共用同一函数，不能维护第二份模型判断。

Tokenizer JSON body 最大 4 MiB，单段 encode text 最大 2 MiB；decode token ID、chat message、bias entry 和生成 token map 都有独立数量上限，超过上限返回 413。`bias.value` 必须是 `[-100,100]` 内的 JSON number。非 multipart POST 不接受 `Transfer-Encoding`，防止 chunked body 绕过 Content-Length 预检。

- [x] **Step 3：处理非 OpenAI tokenizer 选项**

Patch 将 no-node Android 的 tokenizer 选择固定为 OpenAI-compatible 自动模式；隐藏 llama、mistral、claude、remote kobold/textgen 等选项及其 endpoint，不把这些路径标记为 core implemented。

- [x] **Step 4：增加 scanner 回归断言**

Node 测试必须断言 tokenizer 常量表和模板 count URL 被扫描；OpenAI 三个 endpoint 为 `core.tokenizers/implemented`，其他 tokenizer 为 `unsupported_hidden`。

- [x] **Step 5：验证包体和功能**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.TokenizerControllerTest"
.\gradlew.bat --no-daemon :app:assembleDebug
Set-Location ..
npm run test:no-node
```

Expected: 固定 token vector 通过；APK 中不存在 tokenizer 推理模型、native `.so` 或 Node runtime，且 jtokkit 资源只包含 CL100K/O200K 两个 BPE 词表。

**建议提交（由用户手动触发）：** `feat: 添加 OpenAI 兼容 Tokenizer`

---

### Task 9A：实现 SAF 数据导入导出（已完成）

**目的：** 让角色、聊天、World Info 及其他官方前端生成的业务文件真正保存到用户选择的 SAF 位置，并可通过现有官方导入入口重新读取。该任务只处理普通业务数据互操作，不实现整应用 ZIP 备份恢复，也不处理旧 0.2.x 目录。

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/StapkFileBridge.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/SafExportCoordinator.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExportController.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/StapkFileBridgeTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/SafExportCoordinatorTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ExportControllerTest.kt`
- Create: `transform/no-node/web/stapk-export.js`
- Create: `patches/sillytavern-no-node/0007-stapk-mobile-saf-export.patch`
- Create: `test/no-node/stapk-export-bridge.test.mjs`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/MainActivity.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/TavernWebViewClient.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeAdapterPaths.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExportStore.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpService.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/CharacterController.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ChatController.kt`
- Modify: `scripts/stapk-transform-no-node.mjs`
- Modify: `patches/sillytavern-no-node/series`
- Modify: `transform/no-node/capabilities.json`
- Modify: `transform/no-node/mvp-api-allowlist.json`
- Modify: Android 与 no-node 回归测试和生成后的 `mobile/app/src/main/assets/`

**Interfaces:**

```kotlin
class StapkFileBridge(
    private val sessionNonce: String,
    private val onExport: (token: String, fileName: String, mimeType: String) -> Unit
) {
    @JavascriptInterface
    fun saveExport(nonce: String?, token: String?, fileName: String?, mimeType: String?)
}

class SafExportCoordinator {
    fun createDocumentIntent(fileName: String, mimeType: String): Intent
    fun copy(ticket: ExportTicket, output: OutputStream): Long
}
```

新增 staging route：`POST /api/stapk/exports/create`。服务端 controller 已生成的角色/聊天导出继续通过响应头 `X-stAPK-Export-Token` 交付；World Info、Persona、theme 和附件等浏览器生成内容先以 multipart `file` 暂存，再返回 `{token,fileName,mimeType}`。staging 请求必须携带当前 Activity nonce header，单文件上限 32 MiB，并受 8 个活动 ticket、64 MiB 总暂存量配额约束；文件名、MIME 和扩展名必须符合业务白名单。

- [x] **Step 1：写 bridge 安全测试**

每次 Activity 启动生成 32-byte random `sessionNonce`，只注入当前服务随机端口的 `127.0.0.1` 主 frame。端口未知、端口不匹配和 `localhost` 均 fail closed。bridge 调用必须同时匹配 nonce、43 字符 URL-safe token、安全文件名以及 MIME/扩展名业务配对；nullable JavaScript 参数、错误 nonce、控制字符、路径分隔符、超长文件名、APK MIME 和伪装扩展名均被拒绝。外部 HTTPS 只交给系统浏览器，其他绝对主 frame URL 直接阻止。

- [x] **Step 2：实现 SAF coordinator**

`ACTION_CREATE_DOCUMENT` 使用 ticket MIME 和文件名；用户选择 URI 后才从 `ExportStore.consume(token)` 取出单次 ticket，使用 64 KiB buffer 在后台线程流式复制。取消不消费 token；成功或失败后都显式 release 私有临时文件与配额，并通过 toast 和 Web UI event 报告结果。

- [x] **Step 3：接入 MainActivity**

注册名称固定为 `StapkFiles` 的 JavaScript interface；在可信 loopback 主文档 `onPageFinished` 后用 `evaluateJavascript` 写入不可写、不可配置、不可枚举的 `window.stapkBridgeNonce`。Activity Result 使用独立 request code；配置变化同时保存 pending token 与目标 URI，服务未绑定时进入单项队列，重绑后再消费；ticket metadata 必须与 bridge 参数完全一致。未开启任意 file URL access，nonce 不写日志或持久化文件。

- [x] **Step 4：实现前端两类导出适配**

角色/聊天等 fetch response 导出读取 `X-stAPK-Export-Token` 后直接请求 SAF；官方通用 `download()` 生成的 Blob 通过 `/api/stapk/exports/create` 暂存。附件下载也复用 `download()`，不再绕过 SAF 创建独立 `blob:` URL；非文本附件保存的是已抽取文本，因此追加 `.txt`。staging 异步失败时显示 toast。普通浏览器或没有 bridge 时继续使用 upstream Blob URL，不改变非 Android 行为。辅助模块由 transformer 从 `transform/no-node/web/stapk-export.js` 复制，upstream checkout 不被修改。

- [x] **Step 5：验证转换、构建和设备链路**

```powershell
npm run transform:no-node
npm run test:no-node
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon :app:assembleDebug
```

结果：真实 upstream `release` transform 成功，no-node 测试 58/58、Android JVM 全量重跑和 debug APK 35 个任务全量构建通过。2026-07-15 在 Pixel 8 / Android 15 模拟器卸载旧 `com.stapk.mobile` 后干净安装：角色 JSON 通过 response ticket 打开 SAF、保存并重新导入，角色数量从 1 变为 2；World Info JSON 通过 Blob staging 打开 SAF、保存并由官方导入入口覆盖导入成功。2026-07-16 审查加固后再次卸载旧 app 并干净安装，普通保存及在 SAF 页面旋转设备后的配置变化保存均生成 14 字节 `{"entries":{}}`，私有 exports 目录为空、无崩溃、无 Node/Termux 进程；不带 nonce 的直接 staging POST 返回 `403 export_forbidden`。

严格 `npm run verify:no-node-capabilities` 仍因 23 个可见 `needs_review` 失败；这属于 Checkpoint E，不影响本 Task 的定向验收，但在 Checkpoint E 完成前不得宣称最终 capability gate 通过。

设备验收同时发现独立于 SAF 的兼容缺陷：中文 locale 下创建默认 World Info 时，名称在原生兼容层往返后显示为 Unicode replacement character；ASCII 名称的创建、导出和重新导入正常。该问题必须在 Checkpoint E 的 UI/API 对齐阶段补回归测试并修复，不能据此把 World Info Unicode 兼容标记为最终通过。

**建议提交（由用户手动触发）：** `feat: 添加 SAF 数据导入导出桥`

### Task 9B：完整数据备份恢复与 Data Maid（主体完成后可选）

**状态：** 不执行，不阻断 Checkpoint D、主体完成或 0.3.0 发布。只有主体功能、发布链路和 Android 版本矩阵全部收口后，才决定是否另行创建设计和实施计划。

该可选项目与 Task 9A 的普通文件互操作不同：备份 ZIP 预计只包含 `manifest.json`、`user_config/` 和 `user_data/`，不包含 `secrets/`、`logs/`、`web/`、`state/` 或旧 `SillyTavern/`；manifest 需要 schemaVersion、appVersion、createdAt、文件列表和 SHA-256。恢复必须先在隔离目录完成 ZIP traversal、总大小、entry 数、manifest hash 和 JSON 可解析性预检，再创建恢复前快照、原子替换并在失败时回滚。

Data Maid 若未来开发，只能扫描新架构目录；删除必须经过 report、用户选择、finalize 一次性 token 和 delete 时 hash 复核。它不得扫描或删除旧 `SillyTavern/`、secrets、web、logs 和 state，也不得与旧 0.2.x 数据迁移合并开发。

- [ ] 另行评审备份格式、容量限制和跨版本兼容策略。
- [ ] 另行编写 ZIP fixture、预检、事务恢复和回滚测试。
- [ ] 另行实现 `DataArchiveController`、`DataMaintenanceController` 及对应 UI/capability。
- [ ] 另行执行破坏性恢复与删除的专门设备验收。

---

### Task 10：增加诊断日志和损坏数据隔离

**目的：** 让文件损坏、provider 错误和恢复失败有可审计证据，同时保持敏感信息脱敏。

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/DiagnosticLogger.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/DiagnosticLoggerTest.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/DiagnosticsController.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/DiagnosticsControllerTest.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeAdapterPaths.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`
- Modify: existing controllers to use `DiagnosticLogger`

**Interfaces:**

```kotlin
enum class DiagnosticArea { HTTP, STORAGE, PROVIDER, RESTORE }
class DiagnosticLogger(private val logsDir: File) {
    fun event(area: DiagnosticArea, code: String, fields: Map<String, String> = emptyMap())
}
```

Routes: `/api/stapk/diagnostics/summary`、`/api/stapk/diagnostics/export`。

- [x] **Step 1：写脱敏和轮转测试**

输入 `Authorization`、`api_key`、Bearer token、完整 prompt 和 provider response，断言日志只保留状态码、host、耗时、错误 code 和 hash。单日志 2 MiB 轮转，保留 3 份。

- [x] **Step 2：实现 JSONL diagnostic logger**

每行字段固定为 `timestamp,area,code,fields`；fields key allowlist 固定为 `method,path,status,host,durationMs,file,errorClass,sha256`；其他 key 丢弃。

- [x] **Step 3：接入 controller 错误边界**

文件解析失败调用 `AtomicFileStore.quarantine()` 并记录 STORAGE；provider 网络失败记录 PROVIDER；恢复失败记录 RESTORE；正常 HTTP 不逐请求写磁盘，只记录 4xx/5xx。

- [x] **Step 4：实现 summary 和 export**

summary 只返回计数、最后错误时间、quarantine 文件数；export 生成不含用户内容的诊断 ZIP ticket，包含日志、manifest 和 transform metadata。

- [x] **Step 5：验证**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.DiagnosticLoggerTest" --tests "com.stapk.mobile.nativeadapter.DiagnosticsControllerTest"
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```

Expected: 日志脱敏、轮转、导出和全量 JVM 测试通过。

**2026-07-16 执行证据：**

- `DiagnosticLogger` 使用固定 JSONL schema、fields allowlist、2 MiB 单文件上限和 3 份轮转；Provider HTTP/网络错误、HTTP 4xx/5xx、存储隔离和 settings snapshot 恢复失败均已接入对应 area。
- summary/export 路由已注册；设备实测 summary 返回 `counts,lastErrorAt,quarantineFiles`，诊断 ZIP 只包含 `manifest.json`、日志和 transform metadata，manifest 明确 `containsUserContent=false`。
- 设备请求 `/api/stapk/missing?secret=hidden` 返回 404，日志只保存 `/api/stapk/missing`，未保存 query、密钥、prompt 或 provider response。
- `npm run test:no-node`：58/58 通过；Android `:app:testDebugUnitTest --rerun-tasks`：24 个 task 通过；`:app:assembleDebug --rerun-tasks`：35 个 task 通过。
- 设备日志额外暴露 `/css/user.css`、`/backgrounds/__transparent.png` 404 和 `/api/quick-replies/save` 400，转入 Task 11 的 UI/API 对齐处理。

**建议提交（由用户手动触发）：** `feat: 添加脱敏诊断和损坏数据隔离`

---

### Task 11：按 capability 重建 patch queue 和严格 contract

**目的：** 从“隐藏大部分 UI 的 MVP patch”切换到“核心能力默认可见、外部能力按配置显示、排除项稳定隐藏”的最终 patch queue。

**Files:**
- Modify: `patches/sillytavern-no-node/0002-stapk-mobile-hide-unsupported-mvp-features.patch`
- Modify: `patches/sillytavern-no-node/series`
- Create: `patches/sillytavern-no-node/0008-stapk-mobile-capability-gates.patch`
- Modify: `test/no-node/no-node-transform.test.mjs`
- Create: `test/no-node/task11-capability-runtime.test.mjs`
- Modify: `transform/no-node/capabilities.json`
- Create: `transform/no-node/web/stapk-capabilities.js`
- Modify: `scripts/stapk-transform-no-node.mjs`
- Modify: `scripts/stapk-verify-no-node-transform.mjs`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/StaticAssetController.kt`

**Interfaces:**
- Web global: `window.stapkCapabilities`，值来自构建期生成的 `stapk-capabilities.json`。
- Capability API: `isStapkCapabilityAvailable(id)`。
- Final strict command: `npm run verify:no-node-capabilities`，不带豁免参数。

- [x] **Step 1：写最终可见 UI 失败测试**

测试官方 HTML/JS 中以下入口存在且未被 CSS 隐藏：Persona、World Info、Create Group、recent chats、backgrounds、attachments、character import/export、chat import/export、themes、presets。测试 Extensions、主 API 切换、非 OpenAI tokenizer、本地模型、multiuser 和远程访问入口不可见。

- [x] **Step 2：生成 capability runtime 文件**

Transform 把 `capabilities.json` 收敛为不含构建路径的 `sillytavern-web/stapk-capabilities.json`。核心已实现为 true；外部可选默认 false；排除项 false。

- [x] **Step 3：实现前端 capability helper**

Patch 在启动早期加载 capability 文件；`isStapkCapabilityAvailable()` 加载失败时 fail closed。外部 embedding/image/TTS/STT/caption/translation 只显示“配置外部服务”说明，不调用 endpoint。

- [x] **Step 4：清理 MVP CSS 隐藏规则**

删除已经实现能力的 selectors；保留 extension marketplace、SD 本地生成、TTS/STT 本地 provider、vectors、multiuser/admin、主 API 类型切换和 streaming 控件隐藏。

- [x] **Step 5：收敛 allowlist**

所有核心 endpoint 标记 implemented；所有外部能力 endpoint 标记 external_optional 或 unsupported_hidden；所有排除 endpoint 标记 unsupported_hidden。最终 contract 不得有 `exposure=visible,status=needs_review`。

- [x] **Step 6：严格验证**

Run:

```powershell
npm run test:no-node
npm run transform:no-node
npm run transform:no-node:verify
npm run verify:no-node-capabilities
```

Expected: 所有命令成功；`visibleNeedsReview=[]`；transform report 按 capability 输出计数。

**2026-07-16 验证证据：**

- `npm run test:no-node` 通过 64/64；capability helper 覆盖加载失败 fail closed、显式 true、外部服务说明和本地 runtime URL。
- `npm run transform:no-node` 从 SillyTavern `release` commit `8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8` 完整重建并同步 Android assets，API summary 为 `implemented=92`、`external_optional=136`、`unsupported_hidden=110`、`needs_review=0`。
- `npm run transform:no-node:verify` 与不带豁免的 `npm run verify:no-node-capabilities` 均通过，`visibleNeedsReview=[]`、`unassignedEndpoints=[]`。
- Android JVM 全量测试与 `:app:assembleDebug` 通过。Pixel 8 / Android 15 先删除旧 app 后 clean install，官方首页与扩展面板正常渲染；外部能力说明可见，`user.css=200`、`__transparent.png=200/70 bytes`，启动期没有 HTTP 4xx/5xx。
- 设备端 World Info 中文名称完成 edit/list/get/delete 往返，内容未乱码；Quick Reply 空名称不再发出无效保存请求。

**建议提交（由用户手动触发）：** `feat: 按能力契约恢复官方单用户界面`

---

### Task 12：建立一键构建、CI/Release 和最终设备验收

**目的：** 让上游代码到 APK 的转换、验证和发布成为单命令流程，并提供项目主体完成的设备证据。

**Files:**
- Create: `scripts/stapk-build-no-node-apk.mjs`
- Create: `test/no-node/build-orchestrator.test.mjs`
- Modify: `package.json`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/release.yml`
- Modify: `.gitattributes`
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Create: `docs/plan/2026-07-12-stapk-single-user-feature-validation-record.md`
- Modify: `docs/superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md`

**Interfaces:**

```text
npm run build:no-node-apk -- --variant debug --ref release
npm run build:no-node-apk -- --variant release --ref <tag>
```

Output: `output/stapk-mobile-<variant>.apk`、`.sha256`、`api-contract.json`、`stapk-capabilities.json`、`stapk-web-manifest.json`、`transform-report.json`。

- [x] **Step 1：写 orchestrator 命令顺序测试**

注入 fake command runner，断言顺序固定为 Node tests、transform、strict verify、Gradle unit tests、Gradle assemble、copy artifacts、checksum。任何一步非零必须停止，不得留下标记为成功的 output。

- [x] **Step 2：实现一键构建脚本**

脚本参数只接受 `debug|release`；默认 ref 为 `release`；Windows 调用 `mobile/gradlew.bat`，其他平台调用 `mobile/gradlew`。构建前清理 staging output，成功后原子替换正式 output。

- [x] **Step 3：更新 package 和工作流**

增加：

```json
"build:no-node-apk": "node scripts/stapk-build-no-node-apk.mjs"
```

CI 调试构建调用该脚本；Release 使用 tag ref 和 release signing env。两个工作流都上传六类产物，Release 不再只上传 APK 和 checksum。

- [x] **Step 4：删除旧 mobile LFS 规则**

从 `.gitattributes` 删除 `mobile/app/src/main/assets/payload.tgz` 和 `runtime-android-arm64-node*.zip` 规则；Termux 历史目录规则保持不动。验证 active mobile assets 不再存在 LFS pointer。

- [x] **Step 5：写最终验证记录结构**

验证表必须覆盖：clean install、首次加载、重启、Persona、角色 PNG/JSON 导入导出、群组、群聊、recent、聊天 JSONL/TXT、World Info（含 Unicode 名称）和四类绑定、背景、附件、Tokenizer、settings/themes/presets/snapshots、OpenAI-compatible 真实 provider、SAF、无 Node、黑色 WebView surface、Android 7/10/15。

- [x] **Step 6：执行本地全门禁**

Run:

```powershell
git diff --check
npm run build:no-node-apk -- --variant debug --ref release
```

Expected: 单命令成功并生成六类 output；任何 visible `needs_review` 会在 Gradle 之前阻断。

- [x] **Step 7：执行模拟器 clean install**

Run:

```powershell
adb uninstall com.stapk.mobile
adb install output/stapk-mobile-debug.apk
adb shell am start -n com.stapk.mobile/.MainActivity
adb shell pidof node
adb shell ps -A | Select-String -Pattern 'node|server.js|com.stapk.mobile'
```

Expected: 旧 app 先删除；新 app 启动；`pidof node` 无输出；进程列表只有 app/系统 WebView 相关进程。

**2026-07-16 自动构建与 API 35 证据：**

- `test/no-node/build-orchestrator.test.mjs` 覆盖固定命令顺序、六类产物与 checksum、失败保留旧 output、非法参数和 workflow/LFS 契约，4/4 通过。
- `npm run build:no-node-apk -- --variant debug --ref release` 单命令通过；no-node tests 68/68，strict capability、Android JVM tests 和 Gradle assemble 全部通过。
- 生成 `output/stapk-mobile-debug.apk` 及 checksum、API contract、capability runtime、Web manifest、transform report；2026-07-17 Checkpoint C 审查修复后的 APK SHA-256 为 `f359a9b1c09f1abf1fb516fa55d8709e9f7015d280fa46b9bc003d0c54ac4eb6`。
- 通过仓库内 `stapk-emulator` MCP 确保 `Pixel_8` 就绪，删除旧 `com.stapk.mobile` 后安装上述 output APK；Android 15 官方 UI 正常加载，`/version` 返回 `node_runtime=false`，进程列表无 Node。
- 2026-07-17 使用 Step 8 修复后的最新 output APK 再次卸载旧包并 clean install；第 3 秒显示 Android 启动画面而非黑色 WebView，第 18 秒官方 UI 已进入 welcome 页面并记录 `app_ready`，进程列表仍只有 `com.stapk.mobile`。

- [x] **Step 8：执行官方 UI 能力矩阵**

每个能力至少做一次创建、读取、修改、删除或导出；中途 force-stop 并重启，确认持久化。SAF 导出文件在宿主侧检查 MIME、大小和可重新导入性。测试数据只使用新安装产生的数据，不读取 0.2.x 目录。

**2026-07-16 至 2026-07-17 Pixel 8 / API 35 证据：** clean install 后通过官方 UI 创建 Persona `Step8`、角色 `stepchar`、群组 `stepgroup`、群聊和 Unicode World Info `stepworld`；验证 global、character、Persona、group chat 四类绑定在 force-stop 后仍存在。角色 PNG/JSON、World Info、聊天 JSONL/TXT 均经 SAF 导出并重新导入；附件 `step8.txt`、recent chats、群聊文件管理、本地 OpenAI tokenizer 和兼容测试 provider 回复均可用。主题 `steptheme`、OpenAI preset `steppreset` 和 settings snapshot 均落盘并在重启后恢复；snapshot 列表显示真实大小，官方恢复确认流程把 `power_user.noShadows` 从 `true` 恢复为 `false`。账户弹窗保留设置快照和重置设置，并隐藏延期的完整备份与重置全部入口。

**2026-07-17 Checkpoint C 审查处置：** 固定 CSRF 兼容 token 符合当前 loopback 单用户设计，统一会话认证继续按设计延期；“普通聊天无法二次保存”与 JVM 连续保存测试和 API 35 设备证据不符，但聊天原子 move 已补 `REPLACE_EXISTING` 加固非原子 fallback。账户头像现在从 `user-profile.json` 回读经校验的 Data URL，清除后回退 Persona thumbnail；角色 create/edit 统一通过 `AtomicFileStore`。新增测试先出现 2 个预期失败，再修复为通过；最新 APK clean install 后通过设备 loopback 和官方账户弹窗验证头像上传/清除，通过角色 create/edit/get 验证原子写路径，并在 force-stop/relaunch 后确认角色描述仍为 `after`、无 Node/Termux 进程。

- [x] **Step 9：验证真实外部 provider**

配置 OpenAI-compatible endpoint，发送一条非 streaming 请求，记录 provider host、model、HTTP 状态、耗时和回复是否显示；不记录 key、完整 prompt 和完整 response。

**2026-07-17 Pixel 8 / API 35 证据：** 在官方 UI 配置 custom OpenAI-compatible provider `catiecli.sukaka.top` 和模型 `gcli-gemini-3.1-flash-lite`，保持 streaming 关闭。`/models` 返回 HTTP 200（54 个模型，2121 ms），`/chat/completions` 返回 HTTP 200（1625 ms）且响应结构有效；官方 UI 成功显示 provider 回复，界面记录耗时 6.5 秒。force-stop 并重启后 host、model、streaming 设置和 custom secret 均保留，`/models` 再次返回 HTTP 200（999 ms）。`/api/secrets/read` 中唯一记录的 `value` 严格为 `********`，settings 和 diagnostics 均未出现 `api_key_`、`proxy_password`、Authorization 或 Bearer 等敏感字段；验收过程未记录 key、完整 prompt 或完整 response。

- [ ] **Step 10：补齐 Android 版本矩阵**

至少在 API 24、API 29、API 35 各执行安装、启动、文件选择、SAF 导出、一次对话和重启。若只能使用一个本地模拟器，其余版本由 CI emulator job 执行并把报告作为 artifact。

**2026-07-17 延期决定：** 本机不安装 API 24/29 system image；Android 7 和 Android 10 改由用户后续使用真机验收。当前 Step 保持未完成，不阻塞继续执行其他不依赖设备矩阵的收尾检查，但在两版真机证据补齐前不得勾选 Step 10、Step 13 或 Checkpoint F。

- [x] **Step 11：处理 WebView 黑屏验收**

记录从 Activity 启动到 `app_ready` 的时间和黑色 surface 是否超过 3 秒。若超过 3 秒或无法恢复，按 `superpowers:systematic-debugging` 单独定位，在问题关闭前不得把设备验收标记通过。

**2026-07-16 API 35 证据：** force-stop 后冷启动到 `app_ready` 约 12.1 秒；第 3 秒截图为原生“正在启动 SillyTavern...”页面，不是黑色 WebView surface，第 13 秒官方 UI 正常可用。日志显示约 7.8 秒耗在首段 Web 脚本执行前，后续 settings 到 `app_ready` 约 4 秒；当前没有黑色 surface 超过 3 秒或无法恢复的问题，冷启动总耗时作为后续性能优化项保留。

- [x] **Step 12：更新 README、CLAUDE 和设计状态**

文档必须明确：运行时无 Node、构建期需要 Node 20+、一键命令、核心能力清单、外部可选能力、明确排除能力、0.3.0 全新安装要求、已验证 Android 版本和 validation record 链接。

- [ ] **Step 13：最终复核**

Run:

```powershell
git diff --check
rg -n "payload.tgz|runtime-android-arm64-node|ProcessBuilder|server.js|node_modules" mobile scripts .github README.md CLAUDE.md docs/plan/2026-07-12-stapk-single-user-feature-completion-plan.md
npm run test:no-node
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```

Expected: 命中仅出现在禁止项校验或历史说明中；Node 与 JVM 测试通过；validation record 的每一项都有日期、设备/API、结果和证据。

**2026-07-17 可提前执行部分：** `git diff --check` 退出码 0（仅 Windows LF/CRLF 提示），no-node tests 68/68、Android JVM 231 tests（0 failures、0 errors、2 skipped）和一键 debug 构建通过。Node 关键字命中已分类为历史/禁止说明、no-node verifier、legacy 构建脚本，以及浏览器 bundle 内的 Webpack 模块 ID；APK 和 active assets 中不存在 Node runtime、`node_modules` 目录、`server.js` 或 payload/runtime archive。Step 9 真实外部 provider 已通过；Step 10 API 24/29 完成后仍需重跑本 Step，当前不勾选。

**建议提交（由用户手动触发）：** `release: 完成无 Node 单用户功能和发布验收`

---

## 阶段检查点

- [x] **Checkpoint A（Task 0-1）：** capability contract 和安全基础设施可独立审查；不开放新 UI。
- [x] **Checkpoint B（Task 2-3）：** Persona、settings 和角色卡完整；在模拟器 clean install 验证一次 PNG/JSON 导入导出。
- [x] **Checkpoint C（Task 4-6）：** 群组、聊天和 World Info 完整；普通聊天、群组聊天和四类 lorebook 绑定已在 API 35 官方 UI 验证并经 force-stop 复核。
- [x] **Checkpoint D（Task 7-9A）：** 文件、背景、Tokenizer 和 SAF 数据导入导出完整；角色 response ticket 与 World Info Blob staging 均已在 API 35 clean install 上保存并重新导入。完整备份恢复不属于该检查点。
- [x] **Checkpoint E（Task 10-11）：** 诊断、capability gate 和最终 UI/API 对齐；严格 contract 零可见 `needs_review`。
- [ ] **Checkpoint F（Task 12）：** 一键构建、CI/Release、设备矩阵和文档全部收口。

每个 Checkpoint 完成后执行 `superpowers:requesting-code-review`，先修复审查发现再进入下一阶段。

## 明确不在本计划中的内容

- 0.2.x/旧 Node 容器数据迁移。
- 本地 embedding、Stable Diffusion、Whisper、TTS 或其他模型推理。
- 第三方 extension marketplace 和任意 Node/Python/Shell server extension。
- 多用户、管理员、密码恢复、局域网监听和远程访问。
- 非 OpenAI-compatible provider 和 streaming/SSE。
- 远程 embedding、图片、TTS、STT、caption、translation 的具体 provider 实现；本计划只保留 capability gate。
- 完整应用数据 ZIP 备份恢复、事务回滚和 Data Maid；这些能力在主体完成后按 Task 9B 另行立项。

## 最终完成定义

- [x] 官方单用户核心 UI 中所有可见动作有已实现 endpoint 或明确的外部服务说明。
- [x] `npm run verify:no-node-capabilities` 严格通过，零可见 `needs_review`。
- [x] `npm run build:no-node-apk -- --variant debug --ref release` 单命令通过。
- [x] Android JVM 与 Node tests 全部通过。
- [ ] API 24、29、35 的 clean install 核心矩阵通过。
- [x] APK 和运行进程均不存在 Node runtime。
- [x] 角色、Persona、群组、聊天、World Info、背景、附件和设置重启后仍存在。
- [x] PNG/JSON 角色卡、JSONL/TXT 聊天和 World Info 等普通业务数据通过 SAF 导出并可重新导入。
- [x] OpenAI-compatible 非 streaming 真实 provider 验证通过且 secrets 未泄漏。
- [x] CI/Release 上传 APK、checksum 和四类可审计 metadata。
- [x] README、CLAUDE、设计文档和 validation record 与实际行为一致。
- [ ] 发布说明明确 0.3.0 采用全新安装，旧数据迁移属于主体完成后的独立可选项目。
- [ ] 发布说明明确完整应用备份恢复和 Data Maid 同样属于主体完成后的独立可选项目。
