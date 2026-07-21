# stAPK OpenAI-compatible 流式传输实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 让 OpenAI 与 custom OpenAI-compatible preset 可独立选择真正的 SSE streaming，同时修复 stream 状态不一致造成的空回复。

**架构：** 前端继续使用 SillyTavern 上游的 `sse-stream.js` 与 `StreamingProcessor`；Android 用 OkHttp 获取 provider 响应，并通过 NanoHTTPD chunked response 原样桥接 SSE。provider 在 `stream:true` 时返回普通 JSON，则由原生层包装为单事件 SSE，保证不会产生空回复。

**技术栈：** Kotlin、OkHttp 4.12.0、NanoHTTPD 2.3.1、MockWebServer、SillyTavern Web UI、Node.js no-node transform tests、Android WebView/CDP。

## 全局约束

- 当前主线仅修改 `mobile/`、`patches/sillytavern-no-node/`、`test/no-node/` 和相关文档，不修改废弃的 Termux fork。
- APK 运行时不得增加 Node.js、npm、代理进程或公网监听。
- 支持范围保持 OpenAI 与 custom OpenAI-compatible Chat Completions。
- streaming 正常路径透明转发 SSE，不在 Kotlin 中解析 reasoning、tool calls 或多 swipe payload。
- 新安装默认 `stream_openai=false`；preset 和用户设置可以保存 `true`。
- 不记录 API key、messages、SSE chunk、生成正文或完整 provider 响应。
- 保留当前工作区所有已有修改，不回退角色卡导入和 World Info 修复。
- 不自动执行 `git commit` 或 `git push`；每个任务只记录建议提交信息。

---

### Task 1：恢复 preset 与 settings 的 streaming 语义

**文件：**

- 修改：`mobile/app/src/test/java/com/stapk/mobile/nativeadapter/SettingsControllerTest.kt`
- 修改：`mobile/app/src/main/java/com/stapk/mobile/nativeadapter/SettingsController.kt`
- 修改：`test/no-node/no-node-transform.test.mjs`
- 修改：`patches/sillytavern-no-node/0001-stapk-mobile-default-openai-compatible.patch`

**接口：**

- 输入：settings JSON 中的 `oai_settings.stream_openai: Boolean`。
- 输出：`SettingsController.getSettings()` 与 `saveSettings()` 保留该值；SillyTavern preset 切换沿用上游开关行为。

- [x] **Step 1：写 Android settings 失败测试**

在 `SettingsControllerTest.kt` 把现有“强制关闭 streaming”断言改为分别验证默认值和用户值：

```kotlin
@Test
fun `default settings keep streaming disabled`() {
    val settings = currentSettings(controller.getSettings())
    assertFalse(settings.getAsJsonObject("oai_settings").get("stream_openai").asBoolean)
}

@Test
fun `saved OpenAI streaming choice is preserved`() {
    val response = controller.saveSettings(
        """{"main_api":"openai","oai_settings":{"chat_completion_source":"custom","stream_openai":true}}"""
    )
    assertEquals(200, response.statusCode)
    val settings = currentSettings(controller.getSettings())
    assertTrue(settings.getAsJsonObject("oai_settings").get("stream_openai").asBoolean)
}
```

- [x] **Step 2：运行测试并确认失败**

运行：

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.SettingsControllerTest"
```

预期：`saved OpenAI streaming choice is preserved` 失败，实际值仍为 `false`。

- [x] **Step 3：最小修改 settings normalization**

在 `SettingsController.enforceProviderMode` 中保留 source 限制，但删除 streaming 覆盖：

```kotlin
val source = openAi.stringValue("chat_completion_source")
    ?.takeIf { it in SUPPORTED_SOURCES }
    ?: "openai"
openAi.addProperty("chat_completion_source", source)
```

- [x] **Step 4：写 no-node patch 失败测试**

在 `no-node-transform.test.mjs` 读取应用 0001 patch 后的 `public/scripts/openai.js`，断言：

```javascript
assert.doesNotMatch(openAiScript, /settings\.stream_openai\s*=\s*false/);
assert.match(openAiScript, /stream_openai:\s*\['#stream_toggle'/);
assert.match(openAiScript, /oai_settings\.stream_openai\s*=\s*!!\$\('#stream_toggle'\)/);
```

- [x] **Step 5：运行 no-node 测试并确认失败**

运行：

```powershell
node --test test/no-node/no-node-transform.test.mjs
```

预期：补丁产物仍包含 `settings.stream_openai = false`，测试失败。

- [x] **Step 6：更新 0001 patch**

保留 `chat_completion_source` 限制，删除补丁中的：

```javascript
settings.stream_openai = false;
```

不新增自定义 preset 逻辑，直接恢复 SillyTavern 上游的 preset 导入、应用和 checkbox 持久化。

- [x] **Step 7：运行 Task 1 测试**

运行 Android targeted test 和 `node --test test/no-node/no-node-transform.test.mjs`。

预期：两组测试全部通过。

建议提交信息：`fix: 恢复预设流式设置`

---

### Task 2：为原生 HTTP 响应增加可关闭的 chunked body

**文件：**

- 修改：`mobile/app/src/main/java/com/stapk/mobile/nativeadapter/HttpResponse.kt`
- 修改：`mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`
- 新建：`mobile/app/src/test/java/com/stapk/mobile/nativeadapter/NativeHttpStreamingResponseTest.kt`

**接口：**

- 产出：`HttpResponse.stream(statusCode: Int, mimeType: String, body: InputStream, headers: Map<String, String>): HttpResponse`。
- 消费：`NativeHttpServer.toNanoResponse` 将 `bodyStream` 转为 NanoHTTPD `newChunkedResponse`。

- [x] **Step 1：写 response invariant 失败测试**

在新测试中验证流式 body 与其他 body 互斥：

```kotlin
@Test
fun `stream response owns the only response body`() {
    val stream = ByteArrayInputStream("data: ok\n\n".toByteArray())
    val response = HttpResponse.stream(200, "text/event-stream", stream)
    assertSame(stream, response.bodyStream)
    assertNull(response.bodyText)
    assertNull(response.bodyBytes)
    assertNull(response.bodyFile)
}
```

并验证同时传入 `bodyText` 和 `bodyStream` 会抛出 `IllegalArgumentException`。

- [x] **Step 2：运行测试并确认编译失败**

运行：

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.NativeHttpStreamingResponseTest"
```

预期：`HttpResponse.stream` 和 `bodyStream` 尚不存在。

- [x] **Step 3：实现流式 response model**

在 `HttpResponse.kt` 增加 `InputStream` body：

```kotlin
data class HttpResponse(
    val statusCode: Int,
    val mimeType: String,
    val bodyText: String? = null,
    val bodyBytes: ByteArray? = null,
    val headers: Map<String, String> = emptyMap(),
    val bodyFile: File? = null,
    val bodyStream: InputStream? = null
) {
    init {
        require(listOf<Any?>(bodyText, bodyBytes, bodyFile, bodyStream).count { it != null } <= 1) {
            "Only one response body is allowed"
        }
    }

    companion object {
        fun stream(
            statusCode: Int,
            mimeType: String,
            body: InputStream,
            headers: Map<String, String> = emptyMap()
        ) = HttpResponse(statusCode, mimeType, headers = headers, bodyStream = body)
    }
}
```

- [x] **Step 4：让 NanoHTTPD 使用 chunked response**

在 `NativeHttpServer.toNanoResponse` 把 stream 分支放在 fixed body 分支之前：

```kotlin
val nanoResponse = response.bodyStream?.let { bodyStream ->
    newChunkedResponse(status, response.mimeType, bodyStream).also {
        it.setGzipEncoding(false)
    }
} ?: response.bodyFile?.let { bodyFile ->
    // 保留现有 fixed file 分支
}
```

- [x] **Step 5：运行 Task 2 测试**

预期：response invariant 测试通过，现有 `NativeRouterTest` 继续通过。

建议提交信息：`feat: 支持原生分块响应`

---

### Task 3：实现 OkHttp SSE 透明桥接与 JSON 降级

**文件：**

- 新建：`mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ProviderResponseStream.kt`
- 修改：`mobile/app/src/main/java/com/stapk/mobile/nativeadapter/OpenAiCompatibleController.kt`
- 修改：`mobile/app/src/test/java/com/stapk/mobile/nativeadapter/OpenAiCompatibleControllerTest.kt`

**接口：**

- 产出：`ProviderResponseStream(call: Call, response: Response) : InputStream`，关闭时释放 body 并取消 call。
- 产出：`OpenAiCompatibleController.generate` 根据请求 boolean `stream` 返回 fixed JSON 或 chunked SSE `HttpResponse`。
- 依赖：Task 2 的 `HttpResponse.stream`。

- [x] **Step 1：写 stream flag 转发失败测试**

用 MockWebServer 返回延迟 SSE：

```kotlin
server.enqueue(
    MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setChunkedBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n" +
                "data: [DONE]\n\n",
            24
        )
)
val response = controller.generate(streamingRequest(server))
val providerPayload = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
assertTrue(providerPayload.get("stream").asBoolean)
assertNotNull(response.bodyStream)
assertEquals("text/event-stream", response.mimeType.substringBefore(';'))
```

- [x] **Step 2：写首 chunk 可提前读取测试**

MockWebServer 用 `setBodyDelay` 或 throttled chunk 延迟结束；测试先从 `bodyStream` 读取第一条 `data:`，再等待 server 完成，证明 controller 没有缓存全部正文。

- [x] **Step 3：写关闭取消测试**

读取第一条事件后关闭 `bodyStream`，断言 MockWebServer 连接结束，并确认第二次关闭不抛异常。

- [x] **Step 4：写 JSON fallback 测试**

provider 对 `stream:true` 返回：

```json
{"choices":[{"message":{"content":"Fallback"},"finish_reason":"stop"}]}
```

断言原生响应 MIME 为 `text/event-stream`，body 精确包含：

```text
data: {"choices":[{"message":{"content":"Fallback"},"finish_reason":"stop"}]}

data: [DONE]

```

- [x] **Step 5：写非法 stream 类型和非流式回归测试**

断言：

```kotlin
assertEquals(400, controller.generate("""{"stream":"true","messages":[]}""").statusCode)
assertFalse(nonStreamingProviderPayload.get("stream").asBoolean)
assertNull(nonStreamingResponse.bodyStream)
assertTrue(nonStreamingResponse.bodyText!!.contains("Hello"))
```

- [x] **Step 6：运行 targeted tests 并确认失败**

运行：

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.OpenAiCompatibleControllerTest"
```

预期：当前 controller 强制 `stream:false`，所有新增 streaming 测试失败。

- [x] **Step 7：实现 provider-owned stream**

`ProviderResponseStream` 使用委托输入流并保证幂等关闭：

```kotlin
internal class ProviderResponseStream(
    private val call: Call,
    private val response: Response
) : FilterInputStream(requireNotNull(response.body).byteStream()) {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            super.close()
        } finally {
            response.close()
            call.cancel()
        }
    }
}
```

- [x] **Step 8：拆分 non-stream 与 stream execute**

在 `generate` 中严格读取 boolean：

```kotlin
val stream = when {
    !input.has("stream") -> false
    input.get("stream").isJsonPrimitive && input.getAsJsonPrimitive("stream").isBoolean ->
        input.get("stream").asBoolean
    else -> return invalidRequest()
}
payload.addProperty("stream", stream)
return if (stream) executeStreaming(provider, payload.toString()) else executeJson(provider, payload.toString())
```

streaming 成功时不使用 `.use`；非 2xx 时仍在 `.use` 内读取并脱敏错误。

- [x] **Step 9：实现 JSON fallback 和大小限制**

增加常量 `MAX_STREAM_JSON_FALLBACK_BYTES`。当 provider MIME 为 JSON 且首个非空白字符为 `{` 或 `[` 时，受限读取完整 body，用 UTF-8 包装为 SSE；正常 `data:` 流直接返回 `ProviderResponseStream`。

- [x] **Step 10：增加脱敏 streaming diagnostics**

只记录：

```kotlin
mapOf(
    "host" to request.url.host,
    "status" to response.code.toString(),
    "durationMs" to elapsedMs(startedAt).toString(),
    "stream" to "true",
    "errorClass" to exception.javaClass.name
)
```

- [x] **Step 11：运行 Task 3 测试**

预期：新增 streaming、fallback、取消测试及现有 provider 测试全部通过。

建议提交信息：`feat: 实现 OpenAI 流式转发`

---

### Task 4：完成 loopback 增量传输集成测试

**文件：**

- 修改：`mobile/app/src/test/java/com/stapk/mobile/nativeadapter/NativeHttpStreamingResponseTest.kt`
- 必要时修改：`mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`

**接口：**

- 输入：真实 loopback `POST /api/backends/chat-completions/generate`。
- 输出：HTTP 200、`Transfer-Encoding: chunked`、SSE 首事件在结束事件之前可见。

- [x] **Step 1：写端到端 loopback 失败测试**

启动 MockWebServer provider 和 `NativeHttpServer`，通过 `/api/secrets/write` 配置测试 key，再发 streaming generate。provider 先发送事件 A，延迟后发送事件 B 和 `[DONE]`。测试断言：

```kotlin
assertEquals(200, connection.responseCode)
assertEquals("chunked", connection.getHeaderField("Transfer-Encoding"))
assertTrue(reader.readLine().startsWith("data:"))
assertFalse(providerFinished.await(100, TimeUnit.MILLISECONDS))
```

- [x] **Step 2：运行测试并定位传输阻塞**

若第一事件只有 provider 完成后才到达，检查 NanoHTTPD stream branch 是否被 fixed response 或 gzip 路径覆盖；只修正该边界，不改前端 parser。

- [x] **Step 3：验证断连释放**

客户端读取首事件后关闭连接，断言 provider 请求被取消，下一次普通 `/api/ping` 仍能成功。

- [x] **Step 4：运行 nativeadapter 完整测试**

运行：

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.*"
```

预期：全部通过，无线程或连接泄漏导致的测试挂起。

建议提交信息：`test: 覆盖回环流式传输`

---

### Task 5：重生成 Web assets 并执行全量构建验证

**文件：**

- 修改（生成）：`mobile/app/src/main/assets/sillytavern-web/scripts/openai.js`
- 修改（生成）：`mobile/app/src/main/assets/stapk-web-manifest.json`
- 修改（生成）：`mobile/app/src/main/assets/transform-report.json`
- 必要时修改（生成）：`mobile/app/src/main/assets/api-contract.json`
- 更新：`docs/superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md`

**接口：**

- 输入：更新后的 patch queue 和 Kotlin native adapter。
- 输出：可安装 Debug APK、可追溯 Web manifest、通过 verifier 的 no-node assets。

- [x] **Step 1：运行 no-node 全量测试**

```powershell
npm run test:no-node
```

预期：全部通过。

- [x] **Step 2：执行一键构建**

```powershell
npm run build:no-node-apk -- --variant debug --ref release
```

预期：transform、strict capability verifier、Android JVM tests 和 `assembleDebug` 全部通过，APK 发布到 `output/`。

- [x] **Step 3：检查生成差异与敏感信息**

```powershell
git diff --check
rg -n "Bearer |sk-[A-Za-z0-9]|api[_-]?key" mobile/app/src/main/assets mobile/app/src/main/java/com/stapk/mobile/nativeadapter
```

预期：`git diff --check` 无输出；搜索只命中字段名、测试占位或脱敏逻辑，不命中真实 key。

- [x] **Step 4：更新架构状态**

把权威 no-node 设计中的“仅非 streaming”改为“OpenAI-compatible 支持 preset 可选 streaming 与 non-streaming”，并记录 2026-07-21 决策。

建议提交信息：`docs: 更新流式传输能力边界`

---

### Task 6：模拟器真实 provider 验收

**文件：**

- 不修改源码；使用 `output/` APK 和模拟器私有数据。

**接口：**

- 输入：用户已配置的 custom provider、model 和 preset。
- 输出：streaming 与 non-streaming 均产生非空消息，streaming 可观察到增量更新。

- [x] **Step 1：覆盖安装最新 APK**

使用 Android SDK 绝对路径执行 `adb install -r <latest-debug.apk>`，保留模拟器已有 API 配置和角色数据。

- [x] **Step 2：核对版本和前台 Activity**

确认 `versionName=0.3.0-dev`、`versionCode=30000`，前台为 `com.stapk.mobile/.MainActivity`。

- [x] **Step 3：验证非流式**

关闭 preset streaming，发送最小消息。只读取聊天 JSONL 的结构摘要，确认：

```text
mes_type=string
mes_length>0
swipes[0]_type=string
swipes[0]_length>0
```

- [x] **Step 4：验证真流式**

开启同一 preset streaming，通过 CDP `Network` 与 DOM/聊天状态观察：

- 请求 payload 为 `stream:true`。
- 响应 MIME 为 `text/event-stream` 或兼容的 chunked SSE。
- 请求完成前至少观察到两次非递减文本长度，其中一次长度大于 0。
- 最终聊天 JSONL 的 `mes` 和 `swipes[0]` 非空。

- [x] **Step 5：验证停止与 preset 持久化**

再次生成并中途停止，确认 UI 解锁、请求终止且应用进程存活。切换 preset、重启 app，确认每个 preset 的 stream 开关按保存值恢复。

- [x] **Step 6：检查 diagnostics**

输出事件 code 与安全字段名，不输出正文；确认没有 key、prompt、message 或 response body。

- [x] **Step 7：记录真机复测入口**

模拟器通过后，告知用户可连接真机；真机只需覆盖安装并分别发送一条 streaming/non-streaming 消息，不清除数据。

建议提交信息：无，仅记录验收结果。
