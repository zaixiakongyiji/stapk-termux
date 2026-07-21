# stAPK OpenAI-compatible 流式传输设计

日期：2026-07-21
状态：已确认，待实施
范围：`mobile/` 无 Node 原生适配器中的 OpenAI 与 custom OpenAI-compatible Chat Completions

## 背景与根因

当前前端保留了 SillyTavern 的 `stream_openai` 预设字段，但原生后端只实现了固定长度 JSON 响应：

- `SettingsController` 在持久化时强制把 `stream_openai` 改为 `false`。
- Web patch 在初始加载时关闭 streaming，但导入或切换 OpenAI preset 后，preset 可以再次把运行时开关改为 `true`。
- `OpenAiCompatibleController` 无论前端请求什么，都向 provider 强制发送 `stream:false`。
- 前端一旦处于 streaming 模式，就使用 SillyTavern 的 SSE parser 读取响应；普通 JSON 不产生 SSE token，最终保存为空消息。

模拟器复现中，完整角色请求的 provider 响应为 HTTP 200，`choices[0].message.content` 是长度 2936 的字符串，但前端统计为 `0 tokens`。因此根因是前后端对同一请求的 streaming 状态不一致，不是 provider 返回空正文。

## 目标

1. OpenAI 与 custom OpenAI-compatible provider 支持真正的 SSE 流式传输。
2. 每个 OpenAI preset 可以独立保存并恢复 `stream_openai`。
3. streaming 开启时，provider 的增量内容在响应结束前持续显示在聊天界面。
4. streaming 关闭时，保留现有非流式行为。
5. provider 忽略 `stream:true` 并返回普通 JSON 时，不得再次生成空消息。
6. 用户停止生成、WebView 断连或请求失败时，及时释放上游连接。
7. 日志不得记录 API key、prompt、生成正文或完整 provider 响应。

## 上游协议基准

以当前构建工作区中的 SillyTavern 上游实现为基准：

- `src/endpoints/backends/chat-completions.js` 将前端的 `request.body.stream` 传给 OpenAI-compatible provider。
- streaming 开启时调用 `forwardFetchResponse(fetchResponse, response)`。
- `src/util.js` 中的 `forwardFetchResponse` 原样转发 provider 状态和响应字节流，不在服务端解析或重组正常 SSE chunk。
- 前端 `public/scripts/openai.js` 使用 `sse-stream.js` 和 `StreamingProcessor` 解析 `data: ...` 事件并逐步显示。

stAPK 的正常流式路径遵循同一边界：原生层负责可靠转发，不接管 SillyTavern 已有的 SSE 语义。

## 方案选择

### 采用：OkHttp 到 NanoHTTPD 的透明 SSE 桥接

```text
OpenAI preset
    -> stream_openai=true
    -> Web UI POST /api/backends/chat-completions/generate, stream=true
    -> OpenAiCompatibleController 向 provider 发送 stream=true
    -> OkHttp ResponseBody.byteStream()
    -> NanoHTTPD chunked response
    -> WebView fetch ReadableStream
    -> SillyTavern SSE parser
    -> StreamingProcessor 增量更新聊天
```

选择理由：

- 与 SillyTavern 上游行为一致。
- 不需要在 Kotlin 中理解 reasoning、tool calls、多 swipe 等 provider payload。
- NanoHTTPD 2.3.1 已提供 `newChunkedResponse`，OkHttp 4.12.0 已提供流式 `ResponseBody`。
- patch 面集中在平台边界，仍保持无 Node 架构。

### 不采用：在 Kotlin 中解析并重新生成全部 SSE

这会复制 SillyTavern 前端已经具备的协议解析，且容易破坏不同 provider 的 reasoning、tool calls、usage 和扩展字段。

### 不采用：WebView 直接请求 provider

该方案会暴露 API key，并受到 CORS、证书和 provider 浏览器策略限制，不符合现有 secret 隔离边界。

## 组件设计

### Preset 与 settings

- 新安装默认 `stream_openai=false`，保持保守兼容。
- 移除 `SettingsController.enforceProviderMode` 对 `stream_openai` 的强制覆盖，只继续限制 `main_api=openai` 和支持的 source。
- 移除 Web patch 对初始 settings 的强制关闭。
- preset 导入、选择、保存和应用沿用 SillyTavern 上游行为，`stream_openai` 由 preset 自己决定。
- UI 开关保持可操作；切换后同时更新运行时状态和持久化 settings。
- 旧用户已有 settings 保持原值；此前被强制保存为 `false` 的用户不会被自动改成 `true`，需要选择带 streaming 的 preset 或手动开启。

### 原生响应模型

扩展 `HttpResponse`，增加一次性流式 body：

- 流式 body 与 `bodyText`、`bodyBytes`、`bodyFile` 互斥。
- 流式 body 必须可关闭，并拥有上游 OkHttp response/call 的生命周期。
- `NativeHttpServer` 对流式 body 使用 NanoHTTPD `newChunkedResponse`。
- 固定文本、字节和文件响应继续使用 `newFixedLengthResponse`。
- 流式响应禁止 gzip 二次压缩，避免延迟 chunk 或改变 provider 字节流。

### Provider 请求与响应

- `OpenAiCompatibleController.generate` 读取前端 `stream`，缺省为 `false`。
- 仅接受 JSON boolean；非法类型返回 400，不静默猜测。
- 将该值原样放进经过 allowlist 的 provider payload。
- 非流式请求继续读取完整 response body 并返回 JSON。
- 流式请求在收到 provider 响应头后立即返回流式 `HttpResponse`，不得预读完整正文。
- 正常 SSE 响应原样转发 provider 字节，不复制或记录正文。
- 只转发业务所需的 MIME；不转发 hop-by-hop、认证、cookie 或 provider 私有响应头。

### JSON 降级

部分 custom provider 即使收到 `stream:true` 仍会返回普通 JSON。为避免前端再次得到 `0 tokens`：

- 当响应明确为 JSON 时，将完整 JSON 包装成一个 SSE `data:` 事件，并追加 `data: [DONE]`。
- 包装只改变传输 framing，不修改 JSON 字段。
- JSON fallback 设置严格大小上限；超限或无效响应作为 provider 协议错误返回。
- 未声明 JSON 的响应按上游透明转发处理，兼容 `text/event-stream` 及省略标准 MIME 的服务。

### 生命周期与取消

- 流式 InputStream 关闭时，同时关闭 `ResponseBody` 并取消对应 OkHttp call。
- WebView 主动停止、页面离开、NanoHTTPD 客户端断连或写 socket 失败，最终都会关闭该 InputStream。
- provider 正常结束后关闭 response，NanoHTTPD 写入 chunked 结束标记。
- streaming 使用独立 OkHttp client；保留有限的连接、读取和写入超时，避免永久占用 foreground service 线程。

### 错误处理与诊断

- provider 在响应头前返回非 2xx：读取受限错误正文，沿用现有状态码与 API key 脱敏。
- 已开始 streaming 后中断：保留前端已收到内容，关闭连接并记录脱敏事件。
- 新增诊断只记录 `host`、`status`、`durationMs`、`stream=true`、结束类型和异常类。
- 不记录请求 messages、SSE chunk、生成正文、Authorization 或 provider 错误原文。
- provider 返回成功 JSON fallback 时可记录不含正文的 `provider_stream_json_fallback`，便于识别兼容性问题。

## 测试设计

### Android JVM 测试

- settings 默认 streaming 关闭，但保存和读取 `true` 不再被覆盖。
- `OpenAiCompatibleController` 在 streaming 开启时向 MockWebServer 发送 `stream:true`。
- provider 第一个 SSE chunk 在整个响应结束前可从 controller stream 读取。
- 流关闭会取消上游 call，并释放 response body。
- 非流式请求仍发送 `stream:false` 并返回原有 JSON。
- streaming 非 2xx、网络错误和超时不泄露 key 或正文。
- provider 返回 JSON 时输出有效 SSE event 和 `[DONE]`。
- `NativeHttpServer` loopback 集成测试确认响应使用 chunked transfer，并可增量读取多个事件。

### No-node 转换测试

- patch 不再强制 `settings.stream_openai=false`。
- upstream 的 stream toggle、preset 导入和 preset 应用路径保持存在。
- 生成 Web assets 与 patch queue 可重复构建。

### 设备验收

在 Pixel 8 / Android 15 模拟器执行：

1. 同一 preset 关闭 streaming，发送消息并确认一次性返回非空正文。
2. 开启 streaming，发送消息并通过 WebView/CDP 观察内容在请求结束前多次增长。
3. 确认最终 `mes` 和 `swipes[0]` 均为非空字符串。
4. 切换 preset 后确认各自 streaming 设置正确恢复。
5. 中途点击停止，确认请求取消、UI 解锁且应用无崩溃。
6. 重启应用后确认当前 preset 与开关持久化。
7. 检查 diagnostics 和聊天文件，确认没有 API key 泄露。

真机复测沿用同一 APK，重点确认 WebView chunked/SSE 行为与模拟器一致。

## 验收标准

- preset 可以选择并持久化 streaming。
- streaming 开启时 provider 收到 `stream:true`。
- 支持 SSE 的 provider 能在最终响应完成前至少显示一次增量内容。
- streaming 和非 streaming 最终都产生非空助手消息。
- provider 返回普通 JSON 时不会产生空回复。
- 停止生成和断连不会留下持续运行的 provider 请求。
- 全部 no-node tests、Android JVM tests、transform verifier 和 Debug APK 构建通过。
- 模拟器真实 provider 验收通过后再交付真机复测。

## 非目标

- 不在 Kotlin 中统一改写不同厂商的 SSE payload。
- 不增加非 OpenAI-compatible provider。
- 不引入 Node.js、代理进程或公网监听。
- 不把本次功能扩展到图片、语音或其他远程能力的 streaming。
