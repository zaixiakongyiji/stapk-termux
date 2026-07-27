# stAPK 扩展下载超时修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让真机在 GitHub archive 下载超过 OkHttp 默认 10 秒时仍能完成扩展安装，并为失败阶段留下安全诊断。

**Architecture:** `GitHubExtensionClient` 使用仅作用于扩展下载的有界长超时客户端，并在 `ExtensionSourceException` 中携带稳定阶段。`ExtensionController` 保持原有 HTTP 错误合同，在知道操作类型的 catch 边界记录 `operation`、`phase` 和底层 `errorClass`。

**Tech Stack:** Kotlin/JVM、JUnit 4、OkHttp 4.12、MockWebServer、Gson、Robolectric、Android Gradle Plugin。

## Global Constraints

- connect timeout 为 20 秒，read timeout 为 120 秒，write timeout 为 20 秒，call timeout 为 180 秒。
- archive 下载上限保持 64 MiB。
- `/api/extensions/install|update|version` 的状态码和 JSON 错误合同保持不变。
- diagnostics 不得记录 repository URL、owner、repository、branch、commit、响应正文或 archive 内容。
- 不修改 extension registry、staging、journal、recovery 和前端 toast。
- 不执行 `git commit`、`git push`、Tag 或 Release。

---

### Task 1: 扩展专用有界长超时与失败阶段

**Files:**
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionModels.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/GitHubExtensionClient.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/GitHubExtensionClientTest.kt`

**Interfaces:**
- Produces:

```kotlin
enum class ExtensionSourcePhase(val diagnosticValue: String) {
    UNKNOWN("unknown"),
    METADATA("metadata"),
    COMMIT("commit"),
    ARCHIVE_REDIRECT("archive_redirect"),
    ARCHIVE_DOWNLOAD("archive_download"),
    ARCHIVE_READ("archive_read")
}

open class ExtensionSourceException(
    message: String,
    cause: Throwable? = null,
    val phase: ExtensionSourcePhase = ExtensionSourcePhase.UNKNOWN
) : IOException(message, cause)
```

- Produces: `GitHubExtensionClient()` 的生产默认网络超时为 `20s/120s/20s/180s`。
- Preserves: 构造器注入 `OkHttpClient`、redirect 上限和 64 MiB 下载上限。

- [x] **Step 1: 写慢 archive 和阶段分类失败测试**

在 `GitHubExtensionClientTest` 增加一个使用默认 client 的真实慢响应测试。前三个 GitHub API response 立即返回，redirect 后的 archive body 延迟 11 秒：

```kotlin
@Test
fun `default client allows archive response slower than OkHttp ten second default`() {
    val server = MockWebServer()
    server.enqueue(MockResponse().setBody("""{"default_branch":"main"}"""))
    server.enqueue(MockResponse().setBody("""{"sha":"abc123"}"""))
    server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/archive.zip"))
    server.enqueue(
        MockResponse()
            .setHeadersDelay(11, TimeUnit.SECONDS)
            .setBody("archive-body")
            .setHeader("Content-Type", "application/zip")
    )
    server.start()
    try {
        val release = GitHubExtensionClient(
            apiBaseUrl = server.url("/"),
            allowInsecureTestBaseUrl = true
        ).resolve("https://github.com/owner/repo", null)

        assertEquals("archive-body", release.archive.string())
    } finally {
        server.shutdown()
    }
}
```

再增加短 read timeout 注入测试，断言 archive redirect 后的超时异常：

```kotlin
val failure = assertThrows(ExtensionSourceException::class.java) {
    client.resolve("https://github.com/owner/repo", null)
}
assertEquals(ExtensionSourcePhase.ARCHIVE_DOWNLOAD, failure.phase)
assertTrue(failure.cause is SocketTimeoutException)
```

- [x] **Step 2: 运行 RED**

Run:

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.GitHubExtensionClientTest" --rerun-tasks
```

Expected: 慢 archive 测试在约 10 秒后因 `SocketTimeoutException` FAIL，且阶段类型尚不存在导致新阶段断言不能通过。

- [x] **Step 3: 实现最小超时和阶段传播**

在 `GitHubExtensionClient.kt` 增加生产默认 client factory：

```kotlin
private fun extensionHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .callTimeout(180, TimeUnit.SECONDS)
    .build()
```

构造器默认参数改为 `client: OkHttpClient = extensionHttpClient()`。`getJson()` 接收 `ExtensionSourcePhase`；metadata 使用 `METADATA`，commit 使用 `COMMIT`。`execute()` 在 archive 初始请求使用 `ARCHIVE_REDIRECT`，跟随第一个 redirect 后改为 `ARCHIVE_DOWNLOAD`。所有 HTTP、redirect、空 body、无效 JSON 和 `IOException` 异常携带当前 phase。`ExtensionArchiveTransportException` 固定使用 `ARCHIVE_READ`。

- [x] **Step 4: 运行 GREEN**

Run: 与 Step 2 相同。

Expected: `GitHubExtensionClientTest` 全部 PASS，慢 archive 测试约 11 秒完成。

---

### Task 2: 扩展 source 安全诊断

**Files:**
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/DiagnosticLogger.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionController.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionSubsystem.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/DiagnosticLoggerTest.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ExtensionControllerTest.kt`

**Interfaces:**
- Consumes: `ExtensionSourceException.phase: ExtensionSourcePhase`。
- Produces: `extension_source_failed` diagnostics，字段只允许 `operation`、`phase`、`errorClass`。
- Preserves: 客户端仍收到 `502 {"error":"extension_source_unavailable"}`。

- [x] **Step 1: 写 diagnostics allowlist 失败测试**

在 `DiagnosticLoggerTest` 写入：

```kotlin
logger.event(
    DiagnosticArea.HTTP,
    "extension_source_failed",
    mapOf(
        "operation" to "install",
        "phase" to "archive_download",
        "errorClass" to "java.net.SocketTimeoutException"
    )
)
```

解析 JSONL 并断言三个字段均保留。另写非法 phase，断言该字段被丢弃。

- [x] **Step 2: 写 Controller 失败诊断测试**

构造临时 `DiagnosticLogger`，让 `ExtensionSource` 抛出：

```kotlin
ExtensionSourceException(
    "request failed",
    SocketTimeoutException("timeout"),
    ExtensionSourcePhase.ARCHIVE_DOWNLOAD
)
```

调用 `controller.install(INSTALL_BODY)`，断言：

```text
HTTP 502 + extension_source_unavailable
code=extension_source_failed
operation=install
phase=archive_download
errorClass=java.net.SocketTimeoutException
```

同时断言整个 diagnostics 文本不包含 `github.com`、owner、repository 或异常 message。为 `version` 增加操作字段断言，保证不只 mutation 路径会记录。

- [x] **Step 3: 运行 RED**

Run:

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest `
  --tests "com.stapk.mobile.nativeadapter.DiagnosticLoggerTest" `
  --tests "com.stapk.mobile.nativeadapter.ExtensionControllerTest" `
  --rerun-tasks
```

Expected: source phase 被 sanitizer 丢弃，Controller 未生成 `extension_source_failed` 事件，因此 FAIL。

- [x] **Step 4: 实现最小诊断边界**

扩充 `DiagnosticLogger` 的 extension phase allowlist：

```kotlin
val EXTENSION_PHASES = setOf(
    "prepared",
    "files_activated",
    "registry_committed",
    "unknown",
    "metadata",
    "commit",
    "archive_redirect",
    "archive_download",
    "archive_read"
)
```

`ExtensionController` 增加可空 `DiagnosticLogger` 构造参数。`mutationResponse(operation, block)` 在捕获 `ExtensionArchiveTransportException` 或 `ExtensionSourceException` 时先调用：

```kotlin
recordSourceFailure(operation, exception)
```

`recordSourceFailure()` 只记录 allowlist 字段，并沿 cause chain 取最底层 `javaClass.name` 作为 `errorClass`。`version()` 的 source catch 使用 `operation=version`。扩充 diagnostic `operation` allowlist为 `install|update|delete|version`。`createExtensionSubsystem()` 将现有 logger 传给 Controller。

- [x] **Step 5: 运行 GREEN**

Run: 与 Step 3 相同。

Expected: focused diagnostics 和 Controller tests 全部 PASS，现有 502 断言保持不变。

---

### Task 3: 回归与构建验证

**Files:**
- Verify only: `mobile/`
- Update: `docs/superpowers/plans/2026-07-27-stapk-extension-download-timeout.md`

**Interfaces:**
- Consumes: Task 1 和 Task 2 的实现。
- Produces: JVM 全量测试和 Debug APK 构建证据。

- [x] **Step 1: 运行扩展 focused tests**

Run:

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest `
  --tests "com.stapk.mobile.nativeadapter.GitHubExtensionClientTest" `
  --tests "com.stapk.mobile.nativeadapter.ExtensionArchiveInstallerTest" `
  --tests "com.stapk.mobile.nativeadapter.ExtensionControllerTest" `
  --tests "com.stapk.mobile.nativeadapter.ExtensionRecoveryTest" `
  --tests "com.stapk.mobile.nativeadapter.DiagnosticLoggerTest" `
  --rerun-tasks
```

Expected: 全部 PASS。

- [x] **Step 2: 运行全部 Debug unit tests**

Run:

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest --rerun-tasks
```

Expected: BUILD SUCCESSFUL，零失败。

- [x] **Step 3: 构建 Debug APK**

Run:

```powershell
cd mobile
.\gradlew.bat assembleDebug
```

Expected: BUILD SUCCESSFUL，产物位于 `mobile/app/build/outputs/apk/debug/app-debug.apk`。

- [x] **Step 4: 检查工作区范围**

Run:

```powershell
git status --short
git diff --check
git diff --stat
```

Expected: 仅包含本计划列出的源码、测试和文档；无提交、推送、Tag 或 Release。

## 执行结果

- TDD RED 已分别证明：默认 10 秒 archive timeout、JSON body 中断未包装、source `unknown` phase 丢失、GitHub 字段类型错误和精确 timeout 配置未受测试保护。
- 最终验证命令：`.\gradlew.bat testDebugUnitTest assembleDebug --rerun-tasks`。
- 最终结果：391 tests，0 failures，0 errors，4 skipped；43 个 Gradle task 全部重新执行并成功。
- Debug APK：`mobile/app/build/outputs/apk/debug/app-debug.apk`。
- APK SHA-256：`3a040554216f2fdabee047625c641fedca6236fa08e6622ba886d1e57a08c609`。
- 独立复审：0 Critical、0 Important、0 Minor，`Ready to merge: Yes`。
