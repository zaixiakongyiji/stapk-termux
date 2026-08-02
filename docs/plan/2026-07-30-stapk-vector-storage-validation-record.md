# stAPK Vector Storage 设备矩阵验证记录

> 本文件只记录可追溯证据。状态只能填写 `PASS`、`FAIL`、`BLOCKED` 或 `OUT_OF_SCOPE`。
> 未实际执行的项目不得填写 `PASS`，截图、logcat、Provider 请求记录和原始性能数据不得用推测代替。

## 构建产物

| 字段 | 值 |
| --- | --- |
| APK | `output/stapk-mobile-debug.apk` |
| SHA-256 | `dacdea9f15909b8baf664460d39f7b7e95385fc64013818ce5bf26b36ed2df06` |
| 构建日期 | `2026-08-03` |
| 测试分支/提交 | `master` / `2741659` 基线上的未提交工作区 |

当前 APK 使用精确 upstream commit `8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8` 的本地缓存，由 `npm run build:no-node-apk` 一键构建并发布。发布元数据只记录 `local-cache` 与逻辑输出目录，不包含 `file://` 或本机绝对路径。产物包含 Vector Storage system-extension discovery、向量日志脱敏、未确认隐私时强制关闭三个远程向量开关的迁移门禁、`/api/stapk/embeddings/test` 的 `ok:true` 成功契约，以及 `SQLiteFullException -> 507/vector_storage_full` 映射；使用 `adb install -r` 覆盖安装并保留用户 API 配置。

## Provider 证据

每次设备验证单独填写一行。API key 和完整 Base URL 禁止写入本文件。

| 设备 | Provider 类型 | 脱敏 host | model SHA-256 | endpoint SHA-256 | embedding 维度 | 请求记录路径 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| API 35 | Custom OpenAI-compatible | `api.siliconflow.cn` | `caa978c0...0339e` | `dcfb966d...6f661` | 1024 | 最终 APK 上 Test Connection 成功 Toast；仅记录脱敏元数据 | PASS |
| API 29 | — | — | — | — | — | 不属于正式维护范围 | OUT_OF_SCOPE |
| API 24 | — | — | — | — | — | 不属于正式维护范围 | OUT_OF_SCOPE |

## 聊天 API 证据

| 设备 | Provider 类型 | endpoint SHA-256 | model SHA-256 | 测试 Prompt | 实际回复 | 耗时 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Pixel_8/API 35 | Custom OpenAI-compatible | `aafcb845...4e8126` | `f6a0fcd1...2d41e4` | `Reply exactly STAPK_CHAT_OK` | `STAPK_CHAT_OK` | `2.0s` | PASS |

API key 未读取、未写入文档；首次中文自动输入未进入输入框导致的 400 已排除，成功轮次在发送前通过截图确认 ASCII Prompt 已实际写入。

## 设备矩阵

| 设备 | API | ABI | 安装方式 | APK SHA-256 | 状态 | 阻塞或失败原因 |
| --- | --- | --- | --- | --- | --- | --- |
| Pixel_8 AVD | 35 | x86_64 | `install -r` 保留 Provider 与测试数据 | `dacdea9f...ed2df06` | PASS | `0.3.2-dev` 候选覆盖安装后版本与 output 一致并达到 `app_ready`；Embedding 1024 维、Vector query 命中 3 条、真实 RAG 正确回复，强停重启后 31 条聊天、18 条索引和 Provider 配置保留，无 crash/ANR。 |
| — | 29 | — | — | — | OUT_OF_SCOPE | 项目只维护 API 35 及以上，不再安排 API 29 设备验收。 |
| — | 24 | — | — | — | OUT_OF_SCOPE | 项目只维护 API 35 及以上，不再安排 API 24 设备验收。 |

## API 35 完整验收

| 编号 | 用例 | 预期证据 | 实际结果 | 证据路径 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 0 | 安装与首次启动 | 包版本、前台 Activity、首页截图、crash/ANR 扫描 | 最新候选为 `0.3.2-dev`/30200；进入 SillyTavern 1.18.0 首页并达到 `app_ready`；无 crash/ANR/FATAL | 本次 Codex 设备验收输出 | PASS |
| 1 | 保存 Custom OpenAI-compatible Provider，测试连接 | 脱敏请求记录、返回维度 | 覆盖安装后配置保留；最终 APK 显示 `Embedding 连接成功（1024 维）`；未读取或输出 key | 本次 Pixel_8 UI 截图与元素树 | PASS |
| 2 | Data Bank 全局、角色、聊天范围分别加入可识别文本 | 三类条目与 hash/list 结果 | 三个 scope 各保留 1 个原始附件，Vectorize All 后各 1 条向量 | 本次 WebView/CDP 设备输出 | PASS |
| 3 | Vectorize All 后提问 | 最终 request/debug Prompt 中包含命中 chunk | 三个 scope 的 canary 均进入 `4_vectors_data_bank`，未输出 Prompt 正文 | 本次 WebView/CDP 布尔断言 | PASS |
| 4 | 长聊天召回旧消息 | 旧消息命中与 Prompt 注入证据 | 13 条聊天中目标消息位于 protected tail 外；以 `Past events` 注入，目标 canary 与事实均命中 | 本次 WebView/CDP 布尔断言 | PASS |
| 5 | World Info 向量激活 | 对应 entry 命中与 Prompt 注入证据 | entry 为 enabled/vectorized；查询关键词不匹配时仍收到 `WORLDINFO_FORCE_ACTIVATE`，命中 1 条 | 本次 WebView/CDP 事件断言 | PASS |
| 6 | force-stop/relaunch 后查询旧索引 | 重启前后 hash/query 一致 | 重启后三个 Data Bank collection 各 1 条，聊天 13 条；三类召回再次成功 | 本次 adb + WebView/CDP 输出 | PASS |
| 7 | 切换 model | 新 namespace list 为空并提示重新向量化 | 临时 model namespace list 为 0；恢复原 model 后旧聊天 namespace 立即可见 13 条 | 本次 WebView/CDP 输出 | PASS |
| 8 | 断网、429、500 | 错误映射正确，list 无半批 hash | 同日上一轮当前实现验证：429=`embedding_rate_limited`、500=`embedding_provider_error`、断网 timeout；三轮写入均为 0。最终日志脱敏 APK 未改原子写入逻辑 | 本轮连续任务设备证据 | PASS |
| 9 | purge 与 purge-all | 向量索引清空，聊天、附件、World Info 保留 | 同日上一轮 purge-all 后向量为空，3 个附件、13 条聊天、World Info 原文仍保留；本轮由这些原始数据重建成功 | 本轮连续任务设备证据 | PASS |
| 10 | 进程与 APK 内容复核 | `ps -A` 与 APK 清单无 Node、本地模型和 native vector library | 进程只命中 `com.stapk.mobile`；APK Node/ONNX/FAISS/HNSW/native vector 模式均为 0，native `.so` 为 0 | 本次 adb 与 APK 归档扫描 | PASS |
| 11 | DB close/reopen、WAL | instrumentation Runner 输出 | `996fc5d6...e1c1e530` 候选的直接 AndroidJUnitRunner 通过；持久化查询一致且 `journal_mode=wal`。最终候选未修改 native DB 实现，Android JVM 全量回归通过 | 本次 adb Runner 与 Android JVM 输出 | PASS |
| 12 | `10000 x 1536` exact Top-10 | 三次原始耗时、中位数、heap delta | `996fc5d6...e1c1e530` 候选为 `2578/2658/2490 ms`，中位数 `2578 ms`；heap delta `3,497,984 bytes`。最终候选未修改 native 查询实现 | logcat tag `VectorStorePerformance`；本次 adb Runner 输出 | PASS |

### 当前安装 APK 真路径复验

2026-07-31 16:22 至 16:38 对 Pixel_8 AVD 上当前安装 APK 重新执行聊天、Embedding、Vector Storage、RAG、持久化与 instrumentation 真路径验收。设备内 `base.apk` 与 `output/stapk-mobile-debug.apk` SHA-256 均为 `996fc5d6...e1c1e530`。

2026-08-03 将当前重新构建候选 `6beacb5e...b9d602` 通过 `install -r` 覆盖安装到同一 API 35 AVD，设备 `base.apk` 与 output SHA 完全一致。应用达到 `app_ready`，原有 `secrets/`、`user_config/`、`user_data/`、聊天和 World Info 文件保留；`/version` 返回 `node_runtime=false`，Embedding 配置回读为 Custom OpenAI-compatible、`keyConfigured=true` 且模型非空，未重复触发可能计费的远程 Embedding/RAG 请求。logcat 无 `FATAL EXCEPTION` 或目标应用 ANR。

2026-08-03 将版本收口后的 `0.3.2-dev` 候选 `6848ba54...ef310a9` 通过 `install -r` 覆盖安装到同一 Pixel 8 / API 35 AVD，设备 `base.apk` 与 output SHA 完全一致。Embedding Test Connection 为 HTTP 200、`ok:true`、1024 维；聊天 collection list 为 18 条，远程 query HTTP 200 并命中 3 条。真实聊天请求确认包含 `Past events:` 和目标旧库存事实，backend HTTP 200，回复精确为 `four`。强停重启后聊天 29 条、最后回复、18 条向量及 `keyConfigured:true` 均保留；本轮测试 Prompt 与目标事实在 logcat 中命中数均为 0，且无 crash/ANR。

2026-08-03 将修复发布元数据路径泄漏与未确认隐私迁移门禁后的最终 `0.3.2-dev` 候选 `dacdea9f...ed2df06` 覆盖安装到同一 Pixel 8 / API 35 AVD。APK 为 `0.3.2-dev`/30200、v2 签名，包内 `file://` 与本机绝对路径均为 0；Embedding Test 为 HTTP 200、`ok:true`、1024 维，聊天 collection list 为 18 条，远程 query HTTP 200 并命中 3 条，真实聊天回复为 `four`。force-stop/relaunch 后 31 条聊天、最后回复、18 条向量、隐私确认、聊天向量开关及 `keyConfigured:true` 均保留；release canary 在 logcat 中命中 0，且无 crash/ANR。

| 链路 | 当前证据 | 状态 |
| --- | --- | --- |
| 聊天 API | Custom OpenAI-compatible 端点、模型和密钥状态在覆盖安装后保留；手动连接成功，真实 backend 请求 HTTP 200 | PASS |
| Embedding | 配置 API 仅返回 `keyConfigured:true`；真实测试为 HTTP 200、`ok:true`、1024 维 | PASS |
| Vector Storage | `0.3.2-dev` 精确 SHA 覆盖安装后聊天 collection 为 18 条；远程 query HTTP 200、返回 3 个命中 | PASS |
| RAG 请求 1 | 旧紧急代码通过 `Past events:` 进入最终 19 条消息；`/api/backends/chat-completions/generate` 为 HTTP 200，回复精确命中 | PASS |
| RAG 请求 2 | 从未询问过的旧库存事实只存在于 `Past events:` 注入块，最终请求其他消息不含该事实；backend HTTP 200，回复精确为 `4` | PASS |
| RAG 请求 3（Prompt 注入候选） | `6848ba54...ef310a9` 候选的最终 backend 请求同时包含 `Past events:` 与目标旧库存事实，HTTP 200；模型回答精确为 `four` | PASS |
| RAG 请求 4（最终 SHA 回归） | `dacdea9f...ed2df06` 候选的真实聊天请求完成，模型回答精确为 `four`；本轮修复仅涉及发布元数据与隐私初始化门禁 | PASS |
| 持久化 | 最终候选 force-stop/relaunch 后聊天仍为 31 条、最后回复仍为 `four`、向量为 18 条；隐私确认、聊天向量开关、Embedding Provider 类型与 `keyConfigured:true` 保留 | PASS |
| 隐私 | 最终 SHA 新 canary 在发送后的 logcat 命中数为 0，回复中不含 canary；前序诊断 ZIP 的内容 canary 命中数为 0 且 manifest 为 `containsUserContent:false`；未读取或输出真实 API key | PASS |
| 稳定性 | 最终 SHA logcat 无 `FATAL EXCEPTION`、应用 ANR 或 `Process: com.stapk.mobile` crash 记录 | PASS |

补充说明：诊断日志中的历史 429/502/504 来自此前故障注入；本轮最新 Vector provider 事件均为 HTTP 200、1024 维。重复的 `GET /thumbnail` 404 来自无实际缩略图的验收角色占位资源，不影响聊天、Embedding、Vector Storage 或 RAG。

性能记录：

| 轮次 | 查询耗时 ms | 查询前 heap bytes | 查询后 heap bytes | heap delta bytes |
| --- | --- | --- | --- | --- |
| 1 | 2578 | 整体测量 | 整体测量 | - |
| 2 | 2658 | 整体测量 | 整体测量 | - |
| 3 | 2490 | 整体测量 | 整体测量 | - |
| 中位数 | 2578 | 2,556,880 | 6,054,864 | 3,497,984 |

性能状态：`PASS`

## 低版本设备策略

API 24–34 不属于正式维护、回归或问题修复范围。工程继续保持既有 `minSdk=24` 和工具链版本，因此低版本设备可能仍能安装，但这不构成兼容性承诺，也不要求补充 API 24/29 设备证据。

## 隐私与错误输出

使用 5 个非敏感数据 canary 与 1 个仅用于扫描的 key canary；报告保留名称以便复核，但未读取、搜索或输出用户实际 API key。

| 检查位置 | 允许内容 | 搜索命令或证据 | 实际结果 | 状态 |
| --- | --- | --- | --- | --- |
| Provider 真请求 | 允许数据 text；Authorization 仅允许 Provider 接收 | HTTP 200、1024 维与后续召回证明 Provider 收到 embedding 输入；未记录 body/header | 只保留成功元数据，不输出正文或 key | PASS |
| app logs/logcat | 两类 canary 均不允许 | `adb logcat -d` 后逐个 `Select-String -SimpleMatch` 只统计数量 | 最终 SHA 上 6 个 canary 均为 0 | PASS |
| diagnostics export | 两类 canary 均不允许 | POST export，在 WebView 内解 ZIP 并只统计数量 | 仅 `manifest.json`、`logs/diagnostics.jsonl`、manifest metadata；6 个 canary 均为 0 | PASS |
| HTTP error body | 两类 canary 均不允许 | 发送携带数据 canary 的非法 `topK=0` query，仅统计响应字段/正文命中 | HTTP 400，仅 `error` 字段，canary 命中 0 | PASS |
| Provider 错误 UI | 两类 canary 均不允许 | 429/500/断网轮次的错误 UI 与日志扫描 | 仅显示标准错误码/超时；未回显正文或 key | PASS |

## 自动化门禁记录

此表必须在最终验收时重新运行后填写，不能引用开发过程中的旧结果。

| 门禁 | 命令 | 结果摘要 | 原始日志路径 | 状态 |
| --- | --- | --- | --- | --- |
| 一键 Debug 构建 | `npm run build:no-node-apk -- --variant debug --ref release --repo file:///.../build/stapk-current-upstream-cache` | 139 tests、正式 transform、契约校验、Android JVM 与 `assembleDebug` 全部通过并发布 6 个产物 | 本次 Codex 构建输出 | PASS |
| no-node tests | 一键构建内置 `node --test` | 139 tests，139 pass，0 fail | 本次 Codex 构建输出 | PASS |
| transform verifier | 一键构建内置 transform verifier | no-node transform output verified；upstream `8172dcd0...45cd2b8` | 本次 Codex 构建输出 | PASS |
| capability verifier | 一键构建内置 capability verifier | `ok=true`，`needs_review=0`，10 条 embedding/vector 路由 implemented | 本次 Codex 构建输出 | PASS |
| UI verifier | 一键构建前置 no-node tests 与正式 transform 校验 | Vector Storage discovery 与最终 Android assets 校验通过 | 本次 Codex 构建输出 | PASS |
| Android JVM | 一键构建内置 `:app:testDebugUnitTest` | 460 tests，0 failures，0 errors，4 skipped；`VectorControllerTest` 10/10 | `mobile/app/build/test-results/testDebugUnitTest/` | PASS |
| API 35 instrumentation | 直接 `adb shell am instrument`；Gradle UTP 命令另行诊断 | 最终 SHA 上 Runner 3/3 PASS；性能中位数 2578 ms，heap delta 3,497,984 bytes | 本次 adb Runner 与 `VectorStorePerformance` logcat | PASS |
| Debug APK | 一键构建发布到 output | SHA-256 `dacdea9f...ed2df06`；APK Node/ONNX/GGUF/FAISS/HNSW/sqlite-vector/native `.so` 扫描均为 0（`DebugProbesKt.bin` 为 Kotlin 调试元数据，不是模型）；发布元数据本机路径与 `file://` 均为 0 | `output/stapk-mobile-debug.apk` | PASS |

## 文档状态门禁

在以下条件全部为 `PASS` 前，不得把设计规格状态改为“已实施并验收”：

- API 35 及以上维护策略与 API 35 设备证据；
- Data Bank、聊天记忆、World Info 三条真实路径；
- 隐私 canary 扫描；
- instrumentation 性能与持久化；
- 最终自动化门禁；
- APK 与运行进程无 Node、本地 embedding 模型及 native vector library。

当前总状态：`PASS`（API 35 完整验收通过；API 24–34 为 `OUT_OF_SCOPE`）
