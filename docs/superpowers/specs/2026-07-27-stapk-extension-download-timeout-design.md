# stAPK 扩展下载超时修复设计

**日期：** 2026-07-27
**状态：** 已实施并验证
**适用范围：** `mobile/` 原生 Android no-node 应用（包名 `com.stapk.mobile`）

## 1. 问题与证据

真机安装 `ST-Prompt-Template` 时，`/api/extensions/install` 在约 10 秒后返回：

```json
{"error":"extension_source_unavailable"}
```

同一台真机通过系统 `curl` 可以从 GitHub 下载该扩展的 13.7 MB archive，但总耗时约 26 秒。体积更小、在 3.4 秒内完成请求的 `JS-Slash-Runner` 可以通过相同安装 API 正常安装并写入 registry。

代码中的 `GitHubExtensionClient` 直接使用 `OkHttpClient()`，因此继承 10 秒的默认连接、读取和写入超时。所有 `IOException` 又被统一包装成 `ExtensionSourceException`，Controller 最终只返回通用 502，现有 diagnostics 无法区分 GitHub HTTP 错误、连接超时、读取超时或 archive 读取失败。

## 2. 目标

1. 允许速度较慢但仍持续可用的真机网络完成最大 64 MiB 的扩展 archive 下载。
2. 所有网络等待继续有明确上限，避免安装请求无限挂起。
3. 保持现有 `/api/extensions/install|update|version` 状态码和 JSON 错误合同不变。
4. 记录不含用户内容和仓库 URL 的安全诊断字段，能够区分失败阶段与异常类别。
5. 不改变 extension registry、staging、journal、recovery 或 archive 体积限制。

## 3. 非目标

- 不增加自动重试；慢速大文件失败时立即重复下载会放大流量和 GitHub API 压力。
- 不将安装改造成后台任务或轮询协议。
- 不支持私有仓库、Token、GitLab、任意 ZIP URL 或断点续传。
- 不改变前端 toast 文案和 502 `extension_source_unavailable` 合同。

## 4. 方案比较

### 方案 A：扩展专用长超时（采用）

为 `GitHubExtensionClient` 创建扩展专用 `OkHttpClient` 配置。连接超时保持较短，读取超时覆盖慢速 archive，调用总时长设置硬上限。改动集中、不会影响聊天生成等其他网络链路。

### 方案 B：失败后自动重试

可缓解瞬时故障，但无法解决每次都超过 10 秒的稳定慢下载，还会重复下载大型 archive。当前不采用。

### 方案 C：后台安装任务

可提供进度与取消能力，但需要新增任务状态、轮询 API、生命周期恢复和前端交互，超出本次故障范围。当前不采用。

## 5. 组件设计

### 5.1 `GitHubExtensionClient`

生产默认客户端使用以下边界：

- connect timeout：20 秒；
- read timeout：120 秒；
- write timeout：20 秒；
- call timeout：180 秒。

构造器继续允许测试注入 `OkHttpClient`。客户端通过统一的请求执行函数标记 `metadata`、`commit`、`archive_redirect` 和 `archive_download` 阶段；异常继续包装为 `ExtensionSourceException`，但携带稳定的失败阶段。Controller 从异常 cause chain 提取最底层异常类别。

archive 下载上限继续为 64 MiB；读取超时不会放宽体积、redirect 或 URL 校验。

### 5.2 Diagnostics

扩展 source 失败时记录：

```text
area=HTTP
code=extension_source_failed
fields:
  operation=install|update|version
  phase=metadata|commit|archive_redirect|archive_download|archive_read
  errorClass=<安全类名>
```

不得记录 repository URL、owner、repository、branch、commit、响应正文或 archive 内容。Controller 对客户端仍返回现有 502 JSON。

### 5.3 依赖注入

`createExtensionSubsystem()` 将现有 `DiagnosticLogger` 传入 `ExtensionController`。Client 只携带失败阶段，Controller 在知道 `install|update|version` 操作类型的 catch 边界记录诊断。测试使用 `MockWebServer`，不依赖真实 GitHub；慢 archive 回归只保留一个约 11 秒的生产默认超时行为测试，其他错误测试使用注入的短超时客户端。

## 6. 测试设计

1. `MockWebServer` 延迟 archive response 超过注入客户端的短 read timeout，先证明当前客户端失败。
2. 使用生产扩展超时配置面对超过 10 秒等比例缩短后的慢响应，证明 resolve 和 archive 读取成功。
3. 连接/读取失败记录 `operation`、`phase` 和 `errorClass`，且日志不包含 repository URL。
4. GitHub HTTP 404、redirect 上限、64 MiB archive 上限和现有 Controller 502 合同继续通过。
5. 运行扩展 focused JVM tests、全部 Debug unit tests，并构建 Debug APK。

## 7. 验收标准

- 慢 archive 回归测试由 RED 变为 GREEN。
- 快速扩展安装路径和现有错误合同无回归。
- diagnostics 可以确定扩展 source 失败阶段，不泄露仓库信息或响应内容。
- 全部 Debug unit tests 通过，Debug APK 构建成功。
- 不执行 `git commit`、`git push`、Tag 或 Release。
