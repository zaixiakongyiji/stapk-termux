# stAPK 扩展与导入兼容性补充设计

**日期：** 2026-07-17
**状态：** 实施与验收完成
**依赖设计：** `2026-07-09-stapk-no-node-native-adapter-design.md`

## 1. 背景

当前 no-node APK 已能运行 SillyTavern 官方 Web UI，并由 Android Native adapter 提供角色、聊天、预设、World Info、媒体和 OpenAI-compatible 生成等核心 API。但是现有转换规则主动裁剪了扩展市场，只固定加载 `quick-reply`、`attachments`、`gallery`、`expressions` 四个 system extension，同时隐藏扩展安装、管理、Regex 和 Summarize UI。

真机测试还暴露了两个导入问题：

- 角色卡 PNG 在系统文件选择器中选中后没有可见反馈。
- World Info JSON 导入后能看到文件名，但用户没有看到条目内容。

本设计补齐 client-only 扩展生命周期、Regex、Main API Summarize，并用用户提供的真实文件建立导入回归基线。

## 2. 已确认事实

### 2.1 扩展边界

SillyTavern 的第三方 Web extension 与 server plugin 是两套机制：

- Web extension 位于 `scripts/extensions/third-party/`，以同源 JavaScript module 方式加载。
- server plugin 位于仓库 `plugins/`，由 Node 载入并可注册 `/api/plugins/{id}` 路由。

stAPK 仅支持第一类 client-only Web extension。APK 不加入 Node.js、Python、Shell、Git 或 JGit，也不支持 server plugin。

用户指定的两个必需扩展均为 client-only：

- `zonde306/ST-Prompt-Template` 不需要独立后端，主要使用 SillyTavern 浏览器端模块。
- `N0VI028/JS-Slash-Runner` 不需要 Node server plugin，但会调用宿主 API、加载外部 CDN 资源，并调用扩展安装、更新、删除和版本 API。

### 2.2 角色卡样本

`test/测试文件/cc7481f898a8e631.png` 是有效的 Character Card V3 PNG：

- 包含有效的 `chara` 与 `ccv3` `tEXt` chunk。
- CRC、Base64、UTF-8 JSON、`IEND` 和尾随数据均合法。
- 当前 `CharacterCardCodec` 能解码为角色“珞蒹葭”。

因此该样本不能证明 codec 有缺陷。故障范围收窄到：

1. Android DocumentsProvider 返回的 `File.name` 或 MIME 信息不完整，前端因缺少 `.png` 后缀静默退出。
2. WebView 以 chunked multipart 上传，而 Native server 强制要求 `Content-Length`。
3. multipart 到达 controller 后发生文件名校验或持久化错误。
4. 请求成功，但角色列表刷新链路失败。

### 2.3 World Info 样本

`test/测试文件/写实世界V7.82.json` 已经是 SillyTavern object-entry schema：

- `entries` 是 object，共 25 条。
- 条目包含 `uid`、`key`、`keysecondary`、`comment`、`content`、`order`、`position` 等完整字段。
- 当前 `WorldInfoController.normalizeImportedData()` 会对它执行 identity copy。

因此不得为该文件添加无依据的 schema 重写。需要用真实 multipart 导入后调用 `/api/worldinfo/get`，分别验证上传、落盘、读取和渲染边界。

### 2.4 预设样本

`test/测试文件/Izumi 0707.json` 是 OpenAI Chat Completion preset，包含：

- 204 个 prompt。
- 26 条 `extensions.regex_scripts`，其中 16 条启用。
- `extensions.SPreset` 与 `extensions.tavern_helper` 设置。

当前 Preset controller 会原样保存这些字段。Regex、提示词模板和酒馆助手启用后，相关设置才能被 consumer 使用。

## 3. 目标

1. 恢复 Regex system extension。
2. 恢复 Summarize，但仅支持现有 OpenAI-compatible Main API。
3. 恢复第三方扩展发现、安装、启用、禁用、版本检查、更新和删除。
4. 第一版仅接受公开 GitHub 仓库 URL。
5. 支持安装并运行 `ST-Prompt-Template` 与 `JS-Slash-Runner`。
6. 修复角色卡选择后静默无反馈的问题，并兼容无扩展名但 MIME 明确的文件。
7. 允许受大小限制的 chunked multipart 上传。
8. 用真实角色卡和 World Info 文件建立 HTTP 层回归测试。
9. 对导入失败提供可定位到边界的诊断事件和用户提示。

## 4. 非目标

- 不支持 Node/Python/Shell server plugin。
- 不在 APK 中嵌入 Git 或 JGit。
- 不支持私有 GitHub 仓库、GitHub Token 或账号登录。
- 不支持 GitLab、Gitea、自建 Git 或任意 ZIP URL。
- 不支持 extension branch 列表、branch switch、global/local move。
- 不承诺任意第三方扩展都兼容；兼容性取决于其宿主 API 和外部资源。
- 不在本阶段实现旧 0.2.x 数据迁移或完整数据备份恢复。

## 5. 总体架构

### 5.1 数据目录

扩展必须与随 APK 更新的 `filesDir/web/` 分离：

```text
filesDir/
├── web/
├── user_data/
│   └── extensions/
│       ├── ST-Prompt-Template/
│       └── JS-Slash-Runner/
├── state/
│   └── extensions.json
└── quarantine/
```

`filesDir/user_data/extensions/` 保存可执行扩展资源。`filesDir/state/extensions.json` 保存来源、分支、commit SHA、安装时间和更新时间。Web 资源更新不得覆盖扩展目录。

### 5.2 静态资源映射

`StaticAssetController` 在普通 `webDir` fallback 之前增加私有根：

```text
/scripts/extensions/third-party/{folder}/{path}
    -> filesDir/user_data/extensions/{folder}/{path}
```

映射必须复用 `SafePath`，禁止 `..`、绝对路径和目录逃逸。响应使用正确 MIME，并对第三方资源设置 `Cache-Control: no-store`，确保更新后不继续执行旧 bundle。

### 5.3 Native 组件

新增职责分离的组件：

- `GitHubExtensionClient`：解析 GitHub URL，查询默认分支与 commit，下载指定 SHA archive。
- `ExtensionArchiveInstaller`：受限解压、manifest 校验、staging、原子替换和回滚。
- `ExtensionRegistry`：原子读写 `extensions.json`，发现已安装扩展。
- `ExtensionController`：实现与上游前端兼容的 HTTP response contract。

网络层使用项目已有 OkHttp，不新增 Git 依赖。

## 6. 扩展 API

### 6.1 `GET /api/extensions/discover`

返回 system extension 与已安装 local extension：

```json
[
  { "type": "system", "name": "quick-reply" },
  { "type": "system", "name": "regex" },
  { "type": "local", "name": "third-party/ST-Prompt-Template" }
]
```

System extension 从允许列表生成，不扫描并暴露当前 capability 禁止的 system extension。

### 6.2 `POST /api/extensions/install`

请求沿用上游字段：

```json
{ "url": "https://github.com/owner/repo", "global": false, "branch": "" }
```

处理流程：

1. 只接受 `https://github.com/{owner}/{repo}` 与可选 `.git`。
2. 拒绝 credentials、非 GitHub host、异常 path、query 和 fragment。
3. `global=true` 返回明确的 unsupported response。
4. 解析默认分支或请求分支，并获取 commit SHA。
5. 按 SHA 下载 GitHub archive 到 staging。
6. 受限解压并移除 GitHub 单一顶层目录。
7. 校验 `manifest.json`、`js`、`css` 和 locale 相对路径。
8. 拒绝 manifest 声明非空 `requires` 的 Extras module，错误中列出缺失模块。
9. 原子激活扩展目录并写入 registry。
10. 返回上游前端需要的 `version`、`author`、`display_name`、`extensionPath` 和 `folderName`。

### 6.3 `POST /api/extensions/version`

返回：

```json
{
  "currentBranchName": "main",
  "currentCommitHash": "...",
  "isUpToDate": true,
  "remoteUrl": "https://github.com/owner/repo"
}
```

远端检查失败时不得把本地扩展判定为损坏；返回可识别的网络错误，现有扩展继续可用。

### 6.4 `POST /api/extensions/update`

以 registry 中的来源和分支解析最新 SHA。SHA 未变化时直接返回 `isUpToDate=true`。有变化时下载到 staging，完成全部校验后才替换 active 目录；任一步失败都保留旧版本。

### 6.5 `POST /api/extensions/delete`

只允许删除 registry 中存在的安全 folder name。先将目录移动到 temporary trash，再更新 registry，最后删除 temporary；registry 写入失败时恢复目录。

### 6.6 不支持接口

`branches`、`switch`、`move` 不进入第一版。转换 patch 隐藏对应按钮，不允许用户进入必然失败的流程。

## 7. Archive 与安全约束

- archive 下载上限：64 MiB。
- 解压后总上限：128 MiB。
- 单文件上限：32 MiB。
- 文件数上限：10,000。
- 目录深度上限：24。
- redirect 最多 5 次，最终仍必须是 HTTPS。
- 禁止空路径、绝对路径、drive path、`..` 和 canonical path 逃逸。
- archive 必须只有一个顶层目录，顶层内必须存在 `manifest.json`。
- manifest 的 `js`、`css`、`i18n` 目标必须位于扩展目录且真实存在。
- 安装和更新必须在同一文件系统 staging，使用 rename 激活并保留 previous 以支持回滚。

第三方扩展与官方 UI 同源执行，能够读取聊天、角色、设置和 World Info，也能够向网络发送数据。安装 UI 必须保留第三方代码警告，并显示仓库 owner/repo。stAPK 不尝试提供虚假的 JavaScript 权限沙箱。

## 8. Regex 与 Summarize

### 8.1 Regex

- 把 `regex` 加入 system extension 允许列表。
- 放行 `#regex_container`。
- 保留上游首次启用授权流程。
- 使用现有 settings、preset 和 character merge API。
- 使用 `Izumi 0707.json` 验证 26 条脚本原样保留并能被 Regex consumer 读取。

### 8.2 Summarize

- 把 `memory` 加入 system extension 允许列表。
- 默认 source 从 `extras` 改为 `main`。
- 启动时把历史 `extras` 或 `webllm` 设置归一化为 `main`。
- UI 只显示 Main API，不展示不可用 source。
- `/summarize source=extras|webllm` 必须明确拒绝或归一化，不得调用 `/api/summarize`。
- 生成复用现有 `generateQuietPrompt()` / `generateRaw()` 与 `/api/backends/chat-completions/generate`。
- UI 明确沿用上游行为：每次总结是一次额外模型调用，会产生 Token 消耗。

## 9. 角色卡导入可靠性

### 9.1 前端格式判定

角色导入优先使用扩展名，扩展名缺失时使用 MIME：

| MIME | 推断格式 |
|---|---|
| `image/png` | `png` |
| `application/json`、`text/json` | `json` |

无法判定时必须显示 warning，不再静默 `return`。向 `FormData` 添加文件时显式传入带正确扩展名的 normalized filename，保证 Native controller 的 `originalName` 与 `file_type` 一致。

### 9.2 Multipart

Native server 不再强制 multipart 必须带 `Content-Length`：

- 有 `Content-Length` 时继续做格式和总大小预检。
- 无 `Content-Length` 或使用 `Transfer-Encoding: chunked` 时允许进入流式解析。
- `NanoFileUpload.sizeMax`、`fileSizeMax`、part count、header size 和逐字节 copy limit 继续作为硬限制。
- 超限统一返回 `413`，格式错误返回 `400`。

### 9.3 诊断

角色导入失败至少区分：

- `character_file_type_unknown`
- `multipart_invalid`
- `upload_too_large`
- `character_metadata_invalid`
- `character_persist_failed`

诊断日志只记录 MIME、显示名、大小、endpoint 和错误码，不记录角色卡 JSON 内容。

## 10. World Info 导入可靠性

- object-entry schema 必须 identity 保存，真实样本 GET 结果与输入深度相等。
- array-entry Character Book 转换从 entry `deepCopy()` 开始，再覆盖规范字段，未知字段保持原 JSONPath。
- 重复 entry `id` 必须返回 `400`，不得静默覆盖。
- 导入响应增加不破坏上游兼容的 `entry_count`，用于诊断。
- 前端导入成功后清理该名称的 `worldInfoCache`，显式 `await loadWorldInfo()` 并确认 `entries` 是 object，再选择并渲染。
- GET 或渲染前校验失败时显示错误，不得只留下文件名。

## 11. 转换器与能力合同

上游 Web 修改必须进入新的 patch queue 文件，不把 `mobile/app/src/main/assets/sillytavern-web/` 当作唯一源码。正式流程：

1. 在 patched upstream 上形成扩展与导入兼容 patch。
2. 把 patch 加入 `patches/sillytavern-no-node/series`。
3. 更新 `mvp-api-allowlist.json`，把已实现 extension endpoint 移入 implemented。
4. 把 `excluded.extensions` 拆分为 `native.extensions` 和仍排除的 server plugin/Extras endpoint。
5. 运行 `npm run transform:no-node` 重新生成 Android assets 和 API contract。
6. 使用 Node 合同测试固定 patch、UI 和 endpoint 状态。

## 12. 测试策略

### 12.1 JVM 单元测试

- GitHub URL 解析与拒绝规则。
- archive 大小、文件数、深度、路径逃逸和 manifest 校验。
- registry 原子写入、冲突、更新和回滚。
- extension static route 与 MIME。
- controller install/discover/version/update/delete response contract。
- chunked multipart 与 fixed-length multipart。
- 真实角色卡 codec、controller 和 HTTP multipart。
- 真实 World Info HTTP import + GET identity。
- Character Book 未知字段保留与重复 ID 拒绝。

网络测试使用 MockWebServer，不访问真实 GitHub。

### 12.2 Node 合同测试

- patch queue 包含新 patch。
- loader 恢复 `/api/extensions/discover`。
- system extension 包含 Regex 和 Memory。
- CSS 放行 Regex、Memory 和 extension manager。
- Memory 只提供 Main API。
- extension endpoint capability 状态正确。
- 角色导入不再只依赖文件名扩展。
- World Info 导入后显式 reload 并校验 entries。

### 12.3 真机验收

1. 删除旧 App 后安装新 debug APK。
2. 导入 `cc7481f898a8e631.png`，确认出现“珞蒹葭”并可打开。
3. 导入 `写实世界V7.82.json`，确认显示 25 条词条及正文。
4. 导入 `Izumi 0707.json`，确认 Regex 能读取 26 条规则。
5. 使用 URL 安装 `ST-Prompt-Template`，重载后确认设置 UI 和模板功能。
6. 使用 URL 安装 `JS-Slash-Runner`，重载后确认酒馆助手入口和基本脚本功能。
7. 检查更新、禁用、启用和删除。
8. 配置 OpenAI-compatible provider，手动触发 Summarize，确认总结写入并可继续聊天。
9. 导出诊断，确认日志不包含 API key、完整 prompt、角色 JSON 或 World Info 正文。

## 13. 完成标准

- 所有 JVM、Node contract、transform verification 和 debug APK 构建通过。
- 两个真实导入样本在真机通过。
- 两个必需扩展可从 GitHub URL 安装并运行基本功能。
- Regex preset 脚本可见且可执行。
- Summarize 只走 Main API。
- APK 中不存在 Node.js、Git 或 JGit 运行时。
- 扩展安装、更新失败不会破坏已安装版本。
- 不支持功能在 UI 中不可达，并返回明确错误而非无响应。

## 14. 2026-07-17 实施与验收记录

### 14.1 转换与自动测试

- 转换基线为 SillyTavern `release` commit `8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8`。
- `0001-0010` patch queue 可从上游干净生成 Android assets。
- Node 合同测试共 75 项，全部通过。
- `testDebugUnitTest` 与 debug APK 构建通过。
- API contract 结果为 `implemented=98`、`external_optional=136`、`unsupported_hidden=105`、`needs_review=0`。
- debug APK 与五个配套产物由统一构建编排器生成到 `output/`。

### 14.2 导入与扩展验收

- 干净安装后，角色卡 `cc7481f898a8e631.png` 可通过系统文件选择器导入并显示角色“珞蒹葭”。
- `写实世界V7.82.json` 导入后显示 25 条 World Info，正文编辑字段可见。
- `Izumi 0707.json` 导入时显示 Regex 授权弹窗；授权后 Regex 面板实际渲染 26 条 preset scripts。
- `ST-Prompt-Template` 从公开 GitHub URL 安装成功，验证 commit 为 `ada54bb22e3dab0a07e473d383b4c2fe40bc6573`。
- `JS-Slash-Runner` 从公开 GitHub URL 安装成功，验证 commit 为 `f70f9c99ba6f553596ca4cc78d05c76559f15ead`。
- 两个扩展均完成发现、重载、启用、禁用、版本检查、删除和重新安装验证；第三方代码风险提示保留。
- 扩展安装使用 staging、原子目录切换和 registry 持久化，删除后目录与 registry 保持一致。

### 14.3 兼容性修复

- `/version` 的 `agent` 使用上游兼容格式 `SillyTavern:1.18.0:Cohee#1207`，避免第三方扩展把客户端版本解析为空；响应继续声明 `node_runtime=false`。
- capability CSS 按上游真实列布局放行 `#regex_container` 与 `#summarize_container`。最终 WebView 中 Character Expressions、Quick Reply、Summarize 和 Regex 均可见。
- Summarize source 下拉框只包含 `Main API`；`extras` 和 `webllm` 不可选，slash command 合同同样只允许 `main`。
- 首次真实总结请求在 `10.726s` 触发 OkHttp 默认 read timeout，Native adapter 返回 `502`；脱敏诊断明确记录 `java.net.SocketTimeoutException`，未记录 key 或 prompt。
- OpenAI-compatible controller 为 `chat/completions` 派生 120 秒 read-timeout client，模型列表和其他请求继续保持原 client 超时。回归测试使用 50ms 基础超时和 200ms 延迟 provider，完成 RED -> GREEN 验证。
- 2026-07-18 使用真实 OpenAI-compatible key 再次手动总结：`/api/backends/chat-completions/generate` 在 `34.6s` 返回 `200`，总结框写入 919 个字符；网络监听中 `/api/summarize` 调用次数为 0，且没有 Promise exception。

### 14.4 诊断与保密验证

- 在最终 APK 上向不存在的本地 endpoint 发送带测试 secret 和 prompt 的失败请求，再导出诊断 ZIP。
- 导出日志只包含 method、endpoint、status 和 `http_error` code；测试 secret 与 prompt 均未写入。
- 诊断 ZIP 只包含 manifest、脱敏 JSONL 和 transform metadata，不包含角色 JSON、World Info 正文或 API key。

### 14.5 Unicode 与内嵌世界书补充验收

- 发现 NanoHTTPD 2.3.1 会把没有 charset 的 JSON body 按 `US-ASCII` 解码，中文在进入 controller 前已经变成 `U+FFFD`；Native HTTP 边界现统一为无 charset JSON 补 `UTF-8`。
- 中文 World Info 的读取和删除已使用真实无 charset HTTP 请求覆盖；`写实世界V7.82.json` 显示 25 条及正文，删除后列表和私有文件均消失。
- `cc7481f898a8e631.png` 的内嵌世界书自动转换为 13 条并绑定角色；同名相同内容复用，同名不同内容使用唯一后缀，角色写入失败只回滚本次新建文件。
- `0010-stapk-mobile-unicode-and-embedded-lorebook.patch` 消费 Native response 的 `embedded_world`，只刷新列表，不执行前端二次保存。
- `Izumi 0707.json` 授权后 Regex 面板实际渲染 26 条中文规则。preset、聊天、角色和 World Info 落盘文件以及重启后的 WebView 均未发现 `U+FFFD`。

### 14.6 延后项

旧 0.2.x 数据迁移与完整应用数据备份恢复不属于本阶段完成门槛。两者只允许在项目主体功能和发布链路完全完成后分别作为可选独立项目开发，并必须各自产出设计、实施计划和风险验证。
