# stAPK 扩展与导入兼容性实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 在 no-node APK 中补齐 GitHub client-only 扩展管理、Regex、Main API Summarize，并修复角色卡与 World Info 真机导入链路。

**架构：** 第三方扩展保存在独立 app-private 目录，通过 Native static route 映射为 SillyTavern 既有 URL；GitHub archive 代替 Git clone，由 OkHttp 下载、受限解压并原子更新。Web UI 修改继续通过 patch queue 从上游生成，Android Native adapter 负责 extension API、multipart 安全和持久化。

**技术栈：** Kotlin/JVM、NanoHTTPD、Commons FileUpload、OkHttp/MockWebServer、Gson、SillyTavern ES modules、Node.js contract tests、Gradle 8.2。

**执行状态（2026-07-18）：** Task 1-10 已完成；真实 Summarize 请求已验证只调用 Main API，并为非流式生成补充 120 秒 read timeout。随后发现的 Unicode、World Info 删除和角色卡内嵌世界书问题已按 `2026-07-18-stapk-unicode-and-embedded-lorebook-fix-plan.md` 完成修复与干净安装验收。

## 全局约束

- APK 运行时不得包含 Node.js、Git、JGit、Python 或 Shell。
- 第一版只接受公开 `https://github.com/{owner}/{repo}` 仓库。
- 只支持 client-only Web extension，不支持 server plugin 和 Extras module extension。
- 所有生产代码遵循 RED -> GREEN -> REFACTOR；必须先看到目标测试按预期失败。
- 上游 Web 修改必须进入 `patches/sillytavern-no-node/`，再由转换器生成 Android assets。
- 真实样本不得在日志中输出正文、metadata JSON、prompt 或 API key。
- 不主动执行 git commit/push；每个任务结束只检查 diff，并给出建议提交信息。
- 不修改或回退工作区中与本计划无关的已有变更。

---

## 文件职责图

**新增 Android 文件：**

- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionModels.kt`：扩展 registry 与 GitHub release 数据结构。
- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionRegistry.kt`：扩展 metadata 原子持久化。
- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/GitHubExtensionClient.kt`：GitHub URL、commit 和 archive 网络访问。
- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionArchiveInstaller.kt`：受限解压、manifest 校验和原子目录替换。
- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionController.kt`：`/api/extensions/*` HTTP contract。

**修改 Android 文件：**

- `NativeAdapterPaths.kt`：新增扩展目录和 registry 路径。
- `StaticAssetController.kt`：映射第三方扩展静态资源。
- `NativeHttpServer.kt`：注册 extension routes，兼容 chunked multipart。
- `CharacterController.kt`：保留可诊断错误边界，放宽对规范化文件名的重复约束。
- `WorldInfoController.kt`：entry count、无损 array 转换、重复 ID 检查。

**新增/修改 Web patch：**

- `patches/sillytavern-no-node/0009-stapk-mobile-extension-and-import-compatibility.patch`：恢复扩展发现、Regex、Memory，补文件格式推断与 World Info reload。
- `patches/sillytavern-no-node/series`：加入 `0009`。

**能力合同：**

- `transform/no-node/mvp-api-allowlist.json`
- `transform/no-node/capabilities.json`
- `test/no-node/task10-extension-import-compatibility.test.mjs`

---

### Task 1: 建立真实导入样本与 multipart RED 基线

**Files:**
- Copy: `test/测试文件/cc7481f898a8e631.png` -> `mobile/app/src/test/resources/fixtures/cc7481f898a8e631.png`
- Copy: `test/测试文件/写实世界V7.82.json` -> `mobile/app/src/test/resources/fixtures/real-world-v7.82.json`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/CharacterCardCodecTest.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/CharacterControllerTest.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/WorldInfoControllerTest.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/NativeRouterTest.kt`

**Interfaces:**
- Consumes: `CharacterCardCodec.decodePng(bytes)`, `NativeHttpServer`, `/api/characters/import`, `/api/worldinfo/import`, `/api/worldinfo/get`。
- Produces: 可复用的真实 fixture 与 `postMultipart()` 测试 helper。

- [x] **Step 1: 复制真实 fixture 并固定 checksum**

在测试中断言角色卡 SHA-256 为：

```text
F65384B4AC03C5E39FE94669215AEAC52803278C0BFA42F5E56E3FBE71A428CD
```

世界书断言 `entries.size() == 25`。

- [x] **Step 2: 增加 codec 真实样本基线测试**

```kotlin
@Test
fun `decodes supplied ccv3 png fixture`() {
    val bytes = fixture("cc7481f898a8e631.png")
    val decoded = CharacterCardCodec().decodePng(bytes)
    assertEquals("png-ccv3", decoded.sourceFormat)
    assertEquals("珞蒹葭", decoded.json.getAsJsonObject("data").get("name").asString)
    assertArrayEquals(bytes, decoded.avatarBytes)
}
```

该测试用于排除 codec，不要求先失败。

- [x] **Step 3: 写 chunked multipart RED 测试**

使用 raw socket 或未知长度 OkHttp `RequestBody` 向 `/api/characters/import` 发送：

```http
Transfer-Encoding: chunked
Content-Type: multipart/form-data; boundary=stapk-real-card
```

断言期望 `200` 与 `file_name`，当前预期因缺少 `Content-Length` 得到 `400`。

- [x] **Step 4: 写无扩展名但 MIME 明确的前端合同 RED 测试**

在 Node test 中要求正式资产包含：

```js
function resolveCharacterImportFormat(file) {
  // extension first, MIME fallback
}
```

并要求无法判定时调用 `toastr.warning`，当前预期失败。

- [x] **Step 5: 写真实 World Info HTTP round-trip 测试**

通过真实 multipart 导入 fixture，再 POST `/api/worldinfo/get`：

```kotlin
assertEquals(200, importResponse.status)
assertEquals(200, getResponse.status)
assertEquals(25, returned.getAsJsonObject("entries").size())
assertEquals(
    source.getAsJsonObject("entries").getAsJsonObject("0").get("content"),
    returned.getAsJsonObject("entries").getAsJsonObject("0").get("content")
)
assertEquals(source, returned)
```

若当前测试通过，保留为 identity 回归测试，不伪造失败。

- [x] **Step 6: 运行 RED/基线测试**

Run:

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest --tests "*CharacterCardCodecTest" --tests "*CharacterControllerTest" --tests "*WorldInfoControllerTest" --tests "*NativeRouterTest"
```

Expected: codec 与 World Info identity 通过；chunked multipart 测试按预期失败。

---

### Task 2: 修复角色卡格式推断与 chunked multipart

**Files:**
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/CharacterController.kt`
- Create: `patches/sillytavern-no-node/0009-stapk-mobile-extension-and-import-compatibility.patch`
- Modify: `patches/sillytavern-no-node/series`
- Test: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/NativeRouterTest.kt`
- Test: `test/no-node/task10-extension-import-compatibility.test.mjs`

**Interfaces:**
- Produces: `resolveCharacterImportFormat(file): 'png' | 'json' | null`，支持 fixed-length 与 chunked multipart 的 `parseMultipartRequest()`。

- [x] **Step 1: 让 multipart parser 接受未知总长度**

把总长度读取改为可选：

```kotlin
val contentLength = session.headers.entries
    .firstOrNull { (name, _) -> name.equals("content-length", ignoreCase = true) }
    ?.value
    ?.let(::parseContentLength)
if (contentLength != null && contentLength > MAX_MULTIPART_REQUEST_BYTES) {
    throw UploadTooLargeException()
}
```

无长度时仍依赖 `NanoFileUpload.sizeMax`、`fileSizeMax`、`fileCountMax`、`partHeaderSizeMax` 和 `writeBoundedUpload()`。

- [x] **Step 2: 验证 chunked RED 变 GREEN**

Run: Task 1 Step 6 command。
Expected: chunked 角色卡 multipart 返回 `200`，全部目标测试通过。

- [x] **Step 3: 在 patched upstream 实现格式推断**

目标行为：

```js
function resolveCharacterImportFormat(file) {
    const extension = file.name.match(/\.([^.]+)$/)?.[1]?.toLowerCase();
    if (['json', 'png'].includes(extension)) return extension;
    if (file.type === 'image/png') return 'png';
    if (['application/json', 'text/json'].includes(file.type)) return 'json';
    return null;
}
```

上传时显式规范化文件名：

```js
const uploadName = file.name.match(/\.[^.]+$/) ? file.name : `character.${format}`;
formData.append('avatar', file, uploadName);
```

- [x] **Step 4: 为无法识别格式添加可见错误**

```js
if (!format) {
    toastr.warning(t`Only PNG and JSON character cards are supported.`);
    return;
}
```

- [x] **Step 5: 生成 patch 并运行 Node RED/GREEN 测试**

Run:

```powershell
npm test -- --test-name-pattern="character import"
```

Expected: 新增合同测试通过。

---

### Task 3: 固定 World Info 无损导入与显式 reload

**Files:**
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/WorldInfoController.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/WorldInfoControllerTest.kt`
- Modify: `patches/sillytavern-no-node/0009-stapk-mobile-extension-and-import-compatibility.patch`
- Test: `test/no-node/task10-extension-import-compatibility.test.mjs`

**Interfaces:**
- Produces: import response `{ name, entry_count }`；array entry 转换保留未知字段；前端导入后显式 reload。

- [x] **Step 1: 写 array 未知字段和重复 ID RED 测试**

```kotlin
@Test
fun `character book conversion preserves unknown fields at original paths`() {
    // root future_root 与 entry future_entry 在转换后仍位于相同 JSONPath
}

@Test
fun `character book conversion rejects duplicate ids`() {
    // 两条 id=7 的 entry 返回 400，不覆盖第一条
}
```

Expected: 当前未知 entry 字段测试失败，重复 ID 测试失败。

- [x] **Step 2: 从 deepCopy 开始转换 entry**

```kotlin
val entry = source.deepCopy().apply {
    add("uid", uid.deepCopy())
    add("key", source.arrayValue("keys"))
    add("keysecondary", source.arrayValue("secondary_keys"))
    addProperty("order", source.numberValue("insertion_order", 100))
    // 覆盖其余规范字段，但不删除未知字段
}
```

转换 root 同样从 `book.deepCopy()` 开始，再把 `entries` 替换为 object。

- [x] **Step 3: 拒绝重复 ID 并返回 entry count**

在转换阶段检测 `entries.has(uid.asString)` 并返回 invalid response。导入成功 response：

```kotlin
JsonObject().apply {
    addProperty("name", name)
    addProperty("entry_count", data.getAsJsonObject("entries").size())
}
```

- [x] **Step 4: 前端导入后清 cache 并显式加载**

patch 中要求：

```js
worldInfoCache.delete(data.name);
const imported = await loadWorldInfo(data.name);
if (!imported || typeof imported.entries !== 'object' || Array.isArray(imported.entries)) {
    throw new Error('Imported World Info has no valid entries object');
}
```

之后再更新 select 并 `await showWorldEditor(data.name)`。

- [x] **Step 5: 运行 World Info 测试**

Run:

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest --tests "*WorldInfoControllerTest"
```

Expected: 真实样本 identity、未知字段、重复 ID 和 routes 全部通过。

---

### Task 4: 恢复 Regex 与 Main API Summarize

**Files:**
- Modify: `patches/sillytavern-no-node/0009-stapk-mobile-extension-and-import-compatibility.patch`
- Test: `test/no-node/task10-extension-import-compatibility.test.mjs`

**Interfaces:**
- Produces: system extensions `regex`、`memory`；Memory source 恒为 `main`。

- [x] **Step 1: 写 system extension 与 CSS RED 测试**

断言生成资产的 extension list 包含：

```js
{ name: 'regex', type: 'system' }
{ name: 'memory', type: 'system' }
```

并断言 CSS 不隐藏 `#regex_container`、`#memory_container`。

- [x] **Step 2: 写 Memory source RED 测试**

断言：

```text
defaultSettings.source === summary_sources.main
settings.html 只包含 value="main"
loadSettings 将 extras/webllm 归一化为 main
```

- [x] **Step 3: 更新 patch**

在 loader system allowlist 中加入 Regex 和 Memory，调整 capability CSS；在 Memory 中设置：

```js
source: summary_sources.main
```

加载旧设置后执行：

```js
if (extension_settings.memory.source !== summary_sources.main) {
    extension_settings.memory.source = summary_sources.main;
    saveSettingsDebounced();
}
```

- [x] **Step 4: 限制 `/summarize` source**

slash command 收到非 `main` source 时显示 warning 并返回空字符串，不调用 Extras/WebLLM。

- [x] **Step 5: 运行 Node 测试**

Run:

```powershell
node --test test/no-node/task10-extension-import-compatibility.test.mjs
```

Expected: Regex、Memory、CSS 和 source 合同全部通过。

---

### Task 5: 建立扩展私有目录、Registry 与静态路由

**Files:**
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeAdapterPaths.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionModels.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionRegistry.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/StaticAssetController.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ExtensionRegistryTest.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/StaticAssetControllerTest.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/NativeAdapterPathsTest.kt`

**Interfaces:**
- Produces: `ExtensionRecord`、`ExtensionRegistry.list/find/install/remove`、`extensionsDir`、`extensionRegistryFile`。

- [x] **Step 1: 写路径与 Registry RED 测试**

期望路径：

```kotlin
assertEquals(File(root, "user_data/extensions"), paths.extensionsDir)
assertEquals(File(root, "state/extensions.json"), paths.extensionRegistryFile)
```

Registry 测试覆盖空状态、安装、冲突、更新、删除、损坏 JSON quarantine。

- [x] **Step 2: 定义数据结构**

```kotlin
data class ExtensionRecord(
    val folderName: String,
    val repositoryUrl: String,
    val owner: String,
    val repository: String,
    val branch: String,
    val commitSha: String,
    val installedAt: Long,
    val updatedAt: Long
)
```

Registry 使用 `AtomicFileStore` 保存 JSON array。

- [x] **Step 3: 写 static route RED 测试**

在 `extensionsDir/ST-Prompt-Template/dist/index.js` 写 fixture，断言：

```text
GET /scripts/extensions/third-party/ST-Prompt-Template/dist/index.js
status=200
mime=application/javascript
Cache-Control=no-store
```

并断言 `../` 返回 `403`。

- [x] **Step 4: 实现 private root**

```kotlin
PrivateRoot(
    "/scripts/extensions/third-party/",
    paths.extensionsDir,
    cacheControl = "no-store"
)
```

- [x] **Step 5: 运行目标测试**

Run:

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest --tests "*ExtensionRegistryTest" --tests "*StaticAssetControllerTest" --tests "*NativeAdapterPathsTest"
```

Expected: 全部通过。

---

### Task 6: 实现 GitHub client 与受限 Archive installer

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/GitHubExtensionClient.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionArchiveInstaller.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/GitHubExtensionClientTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ExtensionArchiveInstallerTest.kt`

**Interfaces:**
- Produces:

```kotlin
data class GitHubRepository(val owner: String, val repository: String, val canonicalUrl: String)
data class ExtensionRelease(val repository: GitHubRepository, val branch: String, val commitSha: String, val archive: ResponseBody)
interface ExtensionSource {
    fun resolve(url: String, branch: String?): ExtensionRelease
}
```

- [x] **Step 1: 写 URL parser RED 测试**

接受：

```text
https://github.com/zonde306/ST-Prompt-Template
https://github.com/N0VI028/JS-Slash-Runner.git
```

拒绝 HTTP、credentials、query、fragment、额外 path、非 GitHub host、空 owner/repo。

- [x] **Step 2: 用 MockWebServer 写 GitHub API RED 测试**

模拟 repository metadata、commit response、archive redirect 和 archive body，断言 User-Agent、HTTPS policy、redirect limit 和错误码映射。

- [x] **Step 3: 实现 GitHubExtensionClient**

使用 OkHttp 同步请求，按 commit SHA 下载 archive；response body 通过 bounded source 限制为 64 MiB。

- [x] **Step 4: 写 archive RED 测试**

覆盖：正常单根 archive、缺 manifest、path traversal、绝对路径、双根、超过 10,000 文件、单文件超过 32 MiB、总量超过 128 MiB、manifest js/css 逃逸、更新失败回滚。

- [x] **Step 5: 实现 installer**

核心接口：

```kotlin
fun install(release: ExtensionRelease, replacing: ExtensionRecord? = null): InstalledExtension
```

使用 staging 与 previous sibling 目录；仅在解压和 manifest 校验全部成功后 rename 激活。

- [x] **Step 6: 运行目标测试**

Run:

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest --tests "*GitHubExtensionClientTest" --tests "*ExtensionArchiveInstallerTest"
```

Expected: 全部通过，无真实网络请求。

---

### Task 7: 实现 ExtensionController 与 Native routes

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionController.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ExtensionControllerTest.kt`

**Interfaces:**
- Consumes: `ExtensionRegistry`、`ExtensionSource`、`ExtensionArchiveInstaller`。
- Produces: `discover/install/version/update/delete` HTTP endpoints。

- [x] **Step 1: 写 controller RED 测试**

覆盖：

```text
GET  /api/extensions/discover
POST /api/extensions/install
POST /api/extensions/version
POST /api/extensions/update
POST /api/extensions/delete
```

断言上游 response 字段、duplicate 409、unsupported global 400、invalid GitHub URL 400、network 502、archive invalid 422、delete missing 404。

- [x] **Step 2: 实现 system allowlist discover**

```kotlin
val systemExtensions = listOf(
    "quick-reply", "attachments", "gallery", "expressions", "regex", "memory"
)
```

追加 registry local records为 `third-party/{folderName}`。

- [x] **Step 3: 实现 install/version/update/delete**

Controller 只负责校验 request、调用依赖并转换 response，不在 controller 中直接处理 ZIP 或 HTTP。

- [x] **Step 4: 注册 routes**

在 `NativeHttpServer` 注册上述五条 route，确保 `/api/` fallback 不再返回 `endpoint_not_found`。

- [x] **Step 5: 运行 controller 与 route 测试**

Run:

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest --tests "*ExtensionControllerTest"
```

Expected: 全部通过。

---

### Task 8: 恢复上游 Extension manager UI 与能力合同

**Files:**
- Modify: `patches/sillytavern-no-node/0009-stapk-mobile-extension-and-import-compatibility.patch`
- Modify: `transform/no-node/mvp-api-allowlist.json`
- Modify: `transform/no-node/capabilities.json`
- Create: `test/no-node/task10-extension-import-compatibility.test.mjs`

**Interfaces:**
- Produces: Web loader 调用 `/api/extensions/discover`；安装、更新、删除 UI 可达；branch/move/global UI 不可达。

- [x] **Step 1: 写能力合同 RED 测试**

要求 implemented 包含五条 extension endpoint，`excluded.extensions` 不再覆盖整个 `/api/extensions` prefix。

- [x] **Step 2: 恢复 `discoverExtensions()`**

把硬编码 loader 替换为：

```js
const extensions = await discoverExtensions();
extensionNames = extensions.map(x => x.name);
extensionTypes = Object.fromEntries(extensions.map(x => [x.name, x.type]));
```

- [x] **Step 3: 恢复安装与详情入口**

移除 `#extensions_details`、`#extensions_install`、`#third_party_extension_button` 的 capability hide；保留上游第三方代码警告。

- [x] **Step 4: 隐藏不支持操作**

通过 patch/CSS 隐藏 global、move、branch/switch 控件，确保 UI 不触发未实现 endpoint。

- [x] **Step 5: 更新 capability JSON**

新增 `native.extensions` capability，endpoint prefixes 精确到已实现路径；`/api/modules`、`/api/summarize`、server plugin 路由继续 excluded。

- [x] **Step 6: 运行 Node 合同测试**

Run:

```powershell
node --test test/no-node/task10-extension-import-compatibility.test.mjs
```

Expected: 全部通过。

---

### Task 9: 重新生成 Web assets 并完成自动验证

**Files:**
- Regenerate: `mobile/app/src/main/assets/sillytavern-web/**`
- Regenerate: `mobile/app/src/main/assets/api-contract.json`
- Regenerate: `mobile/app/src/main/assets/stapk-web-manifest.json`
- Regenerate: `mobile/app/src/main/assets/transform-report.json`

**Interfaces:**
- Produces: 可打包的正式 Android assets。

- [x] **Step 1: 运行完整 Node 测试**

```powershell
npm run test:no-node
```

Expected: 全部通过。

- [x] **Step 2: 运行转换器**

```powershell
npm run transform:no-node
```

Expected: 0001-0009 patch 全部应用，生成 assets 和 report。

- [x] **Step 3: 验证 capability contract**

```powershell
npm run transform:no-node:verify
npm run verify:no-node-capabilities
```

Expected: 无缺失、重复或 visible-unimplemented extension endpoint。

- [x] **Step 4: 运行完整 JVM 测试**

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`。

- [x] **Step 5: 构建 debug APK**

```powershell
cd ..
npm run build:no-node-apk -- --variant debug --ref release
```

Expected: debug APK 和 metadata 产物生成成功。

---

### Task 10: 模拟器与真机验收

**Files:**
- Modify: `docs/superpowers/specs/2026-07-17-stapk-extension-and-import-compatibility-design.md`，只记录最终验证结论和已知限制。

**Interfaces:**
- Consumes: Task 9 debug APK。
- Produces: 发布前功能证据。

- [x] **Step 1: 模拟器干净安装**

删除 `com.stapk.mobile` 后安装 APK，验证冷启动、设置和 OpenAI-compatible provider 基础流程。

- [x] **Step 2: 导入真实样本**

验证角色“珞蒹葭”、25 条 World Info 和预设 26 条 Regex。

- [x] **Step 3: 安装两个必需扩展**

```text
https://github.com/zonde306/ST-Prompt-Template
https://github.com/N0VI028/JS-Slash-Runner
```

验证安装、重载、启用、禁用、更新检查和删除。酒馆助手外部 CDN 失败必须显示网络错误，不能导致 App 崩溃。

- [x] **Step 4: 验证 Summarize**

使用用户配置的真实 OpenAI-compatible key，手动总结一段聊天；确认只发起 Main API 生成请求。

- [x] **Step 5: 导出并审查诊断**

确认错误日志包含 endpoint/status/error code，但不包含 secret、完整 prompt、角色 JSON 或 World Info 正文。

- [x] **Step 6: 更新设计状态并检查 diff**

```powershell
git status --short
git diff --check
```

Expected: 无 whitespace error；不提交。建议提交信息：

```text
feat: 补齐扩展管理与导入兼容能力
```
