# stAPK 扩展事务恢复与 UI 能力一致性设计

**日期：** 2026-07-21
**状态：** 已实施并完成 API 35 验收
**适用范围：** `mobile/` 原生 Android no-node 应用（包名 `com.stapk.mobile`）

## 1. 背景

当前 OpenAI-compatible streaming 的 Android、SSE 和 preset 数据链路已经实现，但旧 no-node MVP 补丁仍隐藏 `#stream_toggle`。扩展管理也存在相同类型的能力漂移：`/api/extensions/version` 与 `/api/extensions/update` 已实现，`extensions_notify_updates` 仍被旧 CSS 隐藏。

扩展安装已经使用 UUID staging，普通下载、解压和 manifest 校验失败不会提前创建正式 target。然而正式目录激活与 `extensions.json` registry 更新不是同一事务，仍存在以下故障：

- 旧版本留下的同名空目录会永久阻塞重装。
- App 在目录激活后、registry 写入前被强杀，会留下未注册 target。
- 并发安装同一扩展可能互相删除正式目录。
- 更新或删除的 registry 写入失败时，目录和 registry 会产生不一致。
- `.installing-*`、`.previous` 等强杀残留没有启动恢复。

## 2. 目标

1. 恢复 Chat Completion Preset 中的 Streaming 可见开关。
2. 恢复扩展更新通知开关，并保持默认关闭。
3. 保证安装、更新和删除在目录与 registry 两个存储边界上可提交、可回滚、可恢复。
4. 自动处理旧空目录、孤儿 target、旧 `.previous` 和新事务残留，不再要求用户手工删除目录。
5. 串行化所有扩展 mutation，消除重复点击和并发请求导致的状态破坏。
6. App 被强杀后，在下次 Native server 注册路由前自动恢复到一致状态。
7. 补齐 archive 深度、`requires` 和 `i18n` 校验。
8. 用自动测试证明失败后可以直接重试，而不只证明返回了错误。

## 3. 非目标

- 不支持 server plugin、Node.js、Python、Shell、Git 或 JGit。
- 不支持私有 GitHub 仓库、GitHub Token、GitLab、Gitea 或任意 ZIP URL。
- 不支持 extension branch switch、global/local move 或 global install。
- 不实现多个 mutation 并行执行；扩展变更是低频操作，正确性优先于并行吞吐量。
- 不自动永久删除无法确认来源的用户扩展目录；此类目录进入 quarantine。

## 4. 核心约束

- 新安装和缺少 `stream_openai` 的旧 preset 默认 `false`。
- 导入 preset 的显式 boolean 值优先：`true` 必须保持开启，`false` 必须保持关闭。
- `stream_openai` 缺失或类型非法时归一化为 `false`。
- 不同 Chat Completion Preset 分别持久化 streaming 状态，切换和重启后恢复对应值。
- 扩展 archive 下载上限保持 64 MiB。
- archive 解压后总上限保持 128 MiB。
- archive 单文件上限保持 32 MiB。
- archive entry 上限保持 10,000。
- archive 顶层目录以下的最大深度为 24 段。
- 所有正式 Web 修改必须进入 `patches/sillytavern-no-node/` patch queue，再重新生成 Android assets。
- 不记录 API key、扩展文件内容、GitHub 响应正文或聊天正文。
- 不执行 `git commit` 或 `git push`。

## 5. 组件设计

### 5.1 `ExtensionArchiveInstaller`

Installer 只负责下载结果的受限解压和静态校验，不再直接完成正式目录替换。

新接口：

```kotlin
class PreparedExtension(
    val record: ExtensionRecord,
    val stagingDirectory: File
) : Closeable {
    override fun close()
}

fun prepare(
    release: ExtensionRelease,
    replacing: ExtensionRecord? = null
): PreparedExtension
```

`prepare()` 在 `extensions/` 下创建严格命名的唯一 staging：

```text
.stapk-txn-{uuid}.installing
```

完成解压和 manifest 校验后，在 staging 内写入保留文件：

```text
.stapk-extension.json
```

sidecar 内容为完整 `ExtensionRecord`，使用 UTF-8 JSON。Archive 中若已包含同名保留文件，安装必须拒绝，防止第三方内容伪造 stAPK metadata。

`PreparedExtension.close()` 必须幂等删除仍存在的 staging。Controller/Coordinator 使用 `use` 或 `try/finally` 持有它；staging 成功 move 为 target 后原路径不存在，`close()` 不得影响已激活 target。并发冲突、already-installed 和 stale update 等未进入事务的 prepared 结果也必须通过 `close()` 清理。

### 5.2 `ExtensionMutationCoordinator`

所有 install、update、delete 的文件激活和 registry 修改通过同一个进程内 `ReentrantLock` 串行执行。GitHub resolve、archive 下载和 `prepare()` 在锁外完成；进入锁后必须重新读取 registry 并再次检查冲突。

使用单一全局 mutation 锁的原因：

- 当前扩展 mutation 是低频用户操作。
- 单一 journal 同时只描述一个事务，恢复状态可证明。
- 避免不同 folder 但相同 repository、delete/update 交叉等跨 key 竞争。
- 网络和解压位于锁外，不会因慢下载长期阻塞已进入准备阶段之外的工作。

只读的 discover 和 version 不进入 mutation 锁。若 recovery 标记为不可安全继续，install、update、delete 返回 503；其他应用功能继续工作。

`ExtensionRegistry` 增加 `replaceAll(records: List<ExtensionRecord>)`，只允许 Recovery 在 mutation 锁内调用，并通过 `AtomicFileStore` 一次性写入完整去重结果。不得通过多次 `install()` 逐条重建，避免恢复中途再次崩溃时留下半份 registry。

### 5.3 `ExtensionTransactionJournal`

journal 路径：

```text
filesDir/state/extension-transaction.json
```

使用 `AtomicFileStore` 原子写入。Schema 固定为：

```json
{
  "schemaVersion": 1,
  "transactionId": "uuid",
  "operation": "install|update|delete",
  "phase": "prepared|files_activated|registry_committed",
  "folderName": "Repo",
  "oldRecord": null,
  "newRecord": {},
  "stagingName": ".stapk-txn-uuid.installing",
  "backupName": ".stapk-txn-uuid.backup",
  "trashName": ".stapk-txn-uuid.trash"
}
```

所有目录名只保存 basename，读取时必须通过 `SafePath.child()` 解析，禁止 journal 路径逃逸。

### 5.4 `ExtensionRecovery`

`NativeHttpServer` 在注册 extension routes 前同步执行 `recover()`。Recovery 使用同一个 mutation 锁，并输出：

```kotlin
data class ExtensionRecoveryResult(
    val ready: Boolean,
    val recoveredOperations: Int,
    val quarantinedDirectories: Int
)
```

`ready=false` 时，扩展 mutation endpoint 返回：

```json
{"error":"extension_recovery_required"}
```

HTTP status 为 503。Discover、version、静态资源和非扩展功能不受影响。

### 5.5 `ExtensionDirectoryQuarantine`

无法确认来源的目录不直接删除，而是原子移动到：

```text
filesDir/quarantine/extensions/{timestamp}-{uuid}/{originalName}/
```

同批次写入 `diagnostic.json`，只包含 reason、source basename、operation 和时间，不包含扩展文件正文。

## 6. 事务状态机

### 6.1 安装

```text
prepare staging
  -> acquire mutation lock
  -> re-read registry and target
  -> quarantine unregistered target if present
  -> write journal(prepared)
  -> move staging to target
  -> write journal(files_activated)
  -> registry.install(newRecord)
  -> write journal(registry_committed)
  -> remove journal
```

任何 registry commit 前的可捕获失败都删除或 quarantine 新 target，并清理 staging。Registry commit 后只允许做幂等收尾；收尾失败不能把已提交操作报告为失败。

### 6.2 更新

```text
prepare staging with newRecord
  -> acquire mutation lock
  -> re-read current record
  -> reject stale prepared update or return already-up-to-date
  -> write journal(prepared, oldRecord, newRecord)
  -> move target to unique backup
  -> move staging to target
  -> write journal(files_activated)
  -> registry.update(newRecord)
  -> write journal(registry_committed)
  -> delete backup
  -> remove journal
```

Registry commit 前失败时，必须删除或 quarantine 新 target，并把 backup 恢复为 target。Registry commit 后失败时保留新 target，下次启动删除 backup 和 journal。

### 6.3 删除

```text
acquire mutation lock
  -> re-read current record
  -> write journal(prepared, oldRecord)
  -> move target to unique trash
  -> write journal(files_activated)
  -> registry.remove(folderName)
  -> write journal(registry_committed)
  -> delete trash
  -> remove journal
```

Registry 仍有 oldRecord 时恢复 trash；registry 已无记录时删除 trash并完成事务。前端只有在 `response.ok` 时显示删除成功。

## 7. 启动恢复矩阵

Recovery 不只依赖 phase，还必须比较当前 registry 与 journal 的 old/new record，以处理“文件操作完成但下一次 journal 写入前被强杀”的窗口。

| 磁盘与 registry 状态 | 恢复动作 |
|---|---|
| install journal，registry 无 newRecord，target 已存在 | quarantine 新 target，清 staging 和 journal |
| install journal，registry 已有 newRecord，target 存在 | 保留 target，补 sidecar，清 journal |
| update journal，registry 仍是 oldRecord，backup 存在 | quarantine 新 target，backup 恢复为 target，清 journal |
| update journal，registry 已是 newRecord，target 存在 | 保留 target，删除 backup，清 journal |
| delete journal，registry 仍有 oldRecord，trash 存在 | trash 恢复为 target，清 journal |
| delete journal，registry 无 oldRecord | 删除 trash，清 journal |
| registry 有记录、target 存在、sidecar 缺失 | 根据 registry 补写 sidecar |
| registry 有记录、target 缺失且旧 `.previous` 存在 | 恢复 `.previous` 为 target |
| registry 有记录、target 与可恢复 backup 都缺失 | 从 registry 移除失效记录并记录 diagnostic，允许重装 |
| registry 无记录、target sidecar 有效且无冲突 | 从 sidecar 重建 registry |
| registry 无记录、target 无有效 sidecar | target 移入 quarantine，允许重装 |
| 无 journal 的 `.stapk-txn-*.installing` | 删除 |
| 无 journal 的 `.stapk-txn-*.backup|trash` | 移入 quarantine，不猜测状态 |
| 旧 `.folder.previous`，target 缺失且 registry 有记录 | 恢复为 target |
| 旧 `.folder.previous` 的其他组合 | 移入 quarantine |

损坏 journal 先由 `AtomicFileStore` quarantine，再执行无 journal 恢复。损坏 registry 先 quarantine；Recovery 随后仅从有效 sidecar 重建，无 sidecar 目录进入 quarantine。

## 8. Archive 校验

保留现有单一 archive root、重复路径、zip-slip、entry 数、单文件和总展开大小限制，并增加：

1. 顶层目录以下相对路径不得超过 24 段。
2. `requires` 缺失或空数组时允许；非数组、非字符串元素或存在任何非空 module name 时拒绝。
3. `i18n` 缺失时允许；存在时必须是 locale 到相对文件路径的 JSON object。
4. 每个 `i18n` value 必须是非空字符串，通过 `SafePath.child()` 后指向 staging 内真实文件。
5. `.stapk-extension.json` 为保留路径，archive 不得提供。
6. archive ResponseBody 读取途中发生 transport `IOException` 时映射到 502，不归类为非法 ZIP 422。

## 9. UI 与 preset 行为

### 9.1 Streaming

- 从 `0002-stapk-mobile-hide-unsupported-mvp-features.patch` 移除给 Streaming 父容器添加 `stapk-mobile-unsupported` 的修改。
- 最终 `index.html` 中 `#stream_toggle` 的父容器只保留正常 `range-block` class。
- `stream_openai=true|false` 随当前 Chat Completion Preset 保存。
- 导入 preset 时显式 boolean 原样保留；缺失或非法值使用 `false`。
- Android settings normalization 不得把显式 `true` 改写为 `false`。

### 9.2 扩展更新通知

- 在 extension capability 恢复补丁中移除 `label[for="extensions_notify_updates"]` 的隐藏 selector。
- 默认仍为 `false`，用户开启后复用现有 daily version check。
- Install、details、manual update、delete 和 update notification 均可达；global、move、branch/switch 继续隐藏。

### 9.3 删除错误反馈

`deleteExtension()` 必须检查 response status。非 2xx 时显示 `Extension delete failed`，不调用成功 toast，不触发 reload。

## 10. HTTP 错误合同

| 状态 | error code | 条件 |
|---|---|---|
| 400 | `invalid_extension_request` | request schema 非法 |
| 400 | `invalid_github_repository` | GitHub URL 非法 |
| 400 | `global_extensions_not_supported` | global=true |
| 409 | `extension_already_installed` | registry 已有同 repo/folder |
| 409 | `extension_operation_conflict` | prepared operation 已过期或 mutation 冲突 |
| 422 | `invalid_extension_archive` | ZIP 或 manifest 静态校验失败 |
| 502 | `extension_source_unavailable` | GitHub、redirect 或 archive transport 失败 |
| 503 | `extension_recovery_required` | 启动恢复无法安全完成 |
| 500 | `extension_registry_write_failed` | registry commit 失败且已完成回滚 |
| 500 | `extension_transaction_failed` | journal 或目录 mutation 失败且已完成回滚 |

`ready` 可以在锁外作为快速失败标记读取；journal 只能在获得全局 mutation lock 后读取。正常事务持有锁期间 journal 必然存在，并发请求必须等待该事务结束，再根据新的 registry 状态返回 409 或继续，不能因为观察到正常的在途 journal 而误报 503。

Controller 使用明确的 domain exception 一对一映射，不允许通过通用 `Exception` 或 `IOException` 推断状态：

- `ExtensionAlreadyInstalledException` → 409 `extension_already_installed`
- `ExtensionOperationConflictException` → 409 `extension_operation_conflict`
- `InvalidExtensionArchiveException` → 422 `invalid_extension_archive`
- `ExtensionArchiveTransportException` → 502 `extension_source_unavailable`
- `ExtensionRegistryWriteException` → 500 `extension_registry_write_failed`
- `ExtensionTransactionException` → 500 `extension_transaction_failed`
- `ExtensionRecoveryRequiredException` → 503 `extension_recovery_required`

Recovery 对 sidecar 使用以下确定规则：

- 已注册 target 的 sidecar 缺失、损坏或与 registry 不匹配时，以 registry 为权威；先保留性隔离无效 sidecar entry，再写入正确 sidecar，target 保持启用。
- 未注册 target 的 sidecar 缺失、损坏、非普通文件或与 target basename 不匹配时，整个 target 移入 quarantine。
- 未注册 target 的有效 sidecar 若与已注册 record 冲突，未注册 target 移入 quarantine，已注册 target 保留。
- 多个未注册有效 sidecar 互相发生 folder 或 repository 冲突时，所有冲突 target 都移入 quarantine，不选择胜者；其余无冲突 record 一次性重建 registry。

## 11. Diagnostics

新增 extension transaction diagnostic 时只允许以下字段：

```text
operation
phase
folder
result
errorClass
recoveredCount
quarantinedCount
```

禁止记录 repository response body、archive entry 内容、manifest 正文、API key、prompt、message 或模型回复。

## 12. 测试策略

### 12.1 JVM

- 预置同名空目录后安装成功，原目录进入 quarantine。
- 预置非空孤儿目录后安装成功，原文件完整保留在 quarantine。
- 坏 ZIP、缺 manifest、网络中断、激活 move 失败后立即使用相同目录重试成功。
- 使用 barrier 并发安装同一 repo：仅一个成功，另一个 409，target 与 registry 一致。
- 注入 registry install/update/remove 写入失败，断言目录和 registry 同时回到旧状态。
- 为每个 journal phase 预置磁盘状态，重新构造 coordinator/server 后验证恢复矩阵。
- registry 损坏、sidecar 重建、无 sidecar quarantine、旧 `.previous` 恢复。
- depth 25、非空 `requires`、非法/缺失/逃逸 `i18n`、保留 sidecar 路径拒绝。
- 删除 API 失败时前端不显示成功，不 reload。

### 12.2 no-node 合同

- 对最终 patch queue 生成的 HTML 使用结构化 parser 定位 `#stream_toggle`，断言自身及祖先不含隐藏 class。
- 最终 CSS 不得隐藏 Streaming 容器或 `extensions_notify_updates`。
- `stream_openai=true|false/missing/invalid` 的 preset 导入、切换和保存行为固定。
- 所有 `visible_when_implemented` 核心入口维护显式 selector/action 清单；静态隐藏 selector 必须关联 unsupported capability 和理由。

### 12.3 全量与设备验收

1. Android focused extension 与 streaming tests。
2. Android `testDebugUnitTest --rerun-tasks`。
3. `npm run test:no-node`。
4. `npm run transform:no-node:verify`。
5. `npm run verify:no-node-capabilities`。
6. Debug APK build 与 `git diff --check`。
7. 模拟器验证 Streaming 开关、preset 导入与重启持久化。
8. 模拟器验证扩展安装、更新、删除、失败后重试和 App 重启恢复。

## 13. 验收标准

- 普通用户可在 Chat Completion Preset 中直接开关 Streaming。
- 导入 `stream_openai=true` 的 preset 后开关自动开启，且重启后保持。
- 用户可开启扩展更新通知。
- 旧空目录和孤儿 target 不再阻塞安装，且原内容可从 quarantine 恢复。
- 相同扩展并发安装不会破坏成功结果。
- install、update、delete 任意可注入失败后，目录和 registry 保持一致。
- 任一 journal crash window 在重启后自动提交或回滚。
- 失败后无需手工删除目录即可再次安装。
- 全量 verifier、测试、APK 构建和模拟器验收通过。
