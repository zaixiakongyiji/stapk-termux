# stAPK 扩展事务恢复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 恢复 Streaming 与扩展更新通知入口，并让扩展安装、更新、删除在失败、并发和进程强杀后自动恢复到目录与 registry 一致的状态。

**Architecture:** `ExtensionArchiveInstaller` 只生成带 sidecar 的受验证 staging；`ExtensionMutationCoordinator` 在单一全局锁和持久化 journal 下提交目录与 registry；`ExtensionRecovery` 在 extension routes 注册前根据 journal、sidecar 和遗留目录恢复。Web 可见性继续由 SillyTavern patch queue 管理，正式 assets 只通过 transform 生成。

**Tech Stack:** Kotlin/JVM、JUnit 4、Robolectric、Gson、OkHttp/MockWebServer、NanoHTTPD、Node.js `node:test`、parse5、SillyTavern patch queue。

## Global Constraints

- Android runtime 不得引入 Node.js、npm、Git/JGit、Shell 或 server plugin。
- 新安装和 `stream_openai` 缺失/非法的 preset 默认 `false`；显式 boolean `true|false` 必须保留。
- archive 下载上限 64 MiB，解压总上限 128 MiB，单文件上限 32 MiB，entry 上限 10,000，顶层目录以下最大深度 24 段。
- 所有正式 Web 修改必须进入 `patches/sillytavern-no-node/`，再由 transform 生成 Android assets。
- mutation 使用单一进程内全局锁和单一 `state/extension-transaction.json` journal。
- 无法确认来源的用户目录进入 `quarantine/extensions/`，不得直接永久删除。
- diagnostics 不得记录 API key、聊天正文、GitHub response body、archive 内容或 manifest 正文。
- 不执行 `git commit`、`git push`、Tag 或 Release；每个任务通过 `.superpowers/sdd/progress.md` 记录进度。

---

### Task 1: 恢复 UI 入口并固定 preset 归一化合同

**Files:**
- Modify: `patches/sillytavern-no-node/0001-stapk-mobile-default-openai-compatible.patch`
- Modify: `patches/sillytavern-no-node/0002-stapk-mobile-hide-unsupported-mvp-features.patch`
- Modify: `patches/sillytavern-no-node/0009-stapk-mobile-extension-and-import-compatibility.patch`
- Modify: `test/no-node/no-node-transform.test.mjs`
- Modify: `test/no-node/task2-capabilities.test.mjs`
- Modify: `test/no-node/task10-extension-import-compatibility.test.mjs`
- Create: `test/no-node/mobile-implemented-ui-visibility.test.mjs`
- Regenerate: `mobile/app/src/main/assets/sillytavern-web/index.html`
- Regenerate: `mobile/app/src/main/assets/sillytavern-web/css/stapk-mobile.css`
- Regenerate: `mobile/app/src/main/assets/sillytavern-web/scripts/extensions.js`
- Regenerate: `mobile/app/src/main/assets/stapk-web-manifest.json`
- Regenerate: `mobile/app/src/main/assets/transform-report.json`

**Interfaces:**
- Consumes: 完整 no-node patch queue 和最终 HTML/CSS assets。
- Produces: `stream_toggle` 与 `extensions_notify_updates` 可达；`stream_openai` 的 `true|false|missing|invalid` 合同测试。

- [ ] **Step 1: 写最终 assets 的失败测试**

  使用 parse5 定位 `#stream_toggle` 和 `#extensions_notify_updates`，沿父节点向上检查 class，不允许 `stapk-mobile-unsupported`；解析 `stapk-mobile.css` 后断言隐藏 selector 中不含两个入口。增加 preset fixture，断言显式 boolean 保留，missing/字符串/数字/null 归一化为 `false`。

- [ ] **Step 2: 运行 RED**

  Run: `node --test test/no-node/mobile-implemented-ui-visibility.test.mjs test/no-node/no-node-transform.test.mjs test/no-node/task2-capabilities.test.mjs test/no-node/task10-extension-import-compatibility.test.mjs`

  Expected: `stream_toggle` 的祖先仍含 `stapk-mobile-unsupported`，且 CSS 仍隐藏更新通知入口。

- [ ] **Step 3: 最小修改 patch queue**

  从 `0002` 删除 Streaming 父容器 gate；从 extension compatibility 补丁的最终 CSS selector 中删除 `label[for="extensions_notify_updates"]`。保留 global、move、branch/switch 和 server plugin 的隐藏规则。若 upstream preset import 未对非法类型归一化，在 `0001-stapk-mobile-default-openai-compatible.patch` 中增加：

  ```js
  preset.stream_openai = typeof preset.stream_openai === 'boolean'
      ? preset.stream_openai
      : false;
  ```

- [ ] **Step 4: 重新生成正式 assets 并运行 GREEN**

  Run: `npm run transform:no-node`

  Run: `node --test test/no-node/mobile-implemented-ui-visibility.test.mjs test/no-node/no-node-transform.test.mjs test/no-node/task2-capabilities.test.mjs test/no-node/task10-extension-import-compatibility.test.mjs`

  Expected: 所有 focused tests PASS，最终 HTML/CSS 中两个入口可达。

- [ ] **Step 5: 记录任务结果**

  在 `.superpowers/sdd/progress.md` 追加 RED/GREEN 命令、测试计数和 task review 结论，不提交。

---

### Task 2: 将 archive installer 收敛为 PreparedExtension

**Files:**
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionModels.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionRecordCodec.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionRegistry.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionDirectoryOperations.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionArchiveInstaller.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/SafePath.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ExtensionArchiveInstallerTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ExtensionRecordCodecTest.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ExtensionRegistryTest.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/GitHubExtensionClientTest.kt`

**Interfaces:**
- Produces:

  ```kotlin
  class PreparedExtension(
      val record: ExtensionRecord,
      val stagingDirectory: File
  ) : Closeable

  fun ExtensionArchiveInstaller.prepare(
      release: ExtensionRelease,
      replacing: ExtensionRecord? = null
  ): PreparedExtension
  ```

- Produces: staging 名称 `.stapk-txn-{uuid}.installing`，UTF-8 `.stapk-extension.json` sidecar。
- Produces: `internal fun moveDirectoryAtomically(source: File, target: File)`，后续 installer、quarantine、coordinator 与 recovery 只能复用这一原语。
- Produces: `ExtensionRecordCodec.encode(record)` 与严格 `decode(text)`，registry、sidecar 和 journal 共享同一字段校验。
- Consumes later: Task 4 coordinator 获得 `PreparedExtension` 后负责正式目录 mutation。
- Transitional compatibility: 为保证 Task 2 结束时现有 Controller 仍可编译，暂时保留 `@Deprecated("Removed by ExtensionMutationCoordinator in Task 4") fun install(...)`。该 wrapper 必须调用已验证的 `prepare()` 后复用原有 activation 行为，不得形成第二套解压、校验或 sidecar 逻辑；Task 4 接入 coordinator 时删除 wrapper 和 legacy activation。

- [ ] **Step 1: 为 prepare、sidecar 和幂等 close 写失败测试**

  断言 `prepare()` 不创建正式 target；sidecar 通过 `ExtensionRecordCodec` 反序列化后等于 `record`；`close()` 调用两次都删除 staging；archive 自带 `.stapk-extension.json` 时拒绝。Codec 拒绝缺字段、额外字段、错类型和非法 `ExtensionRecord`；registry read/write 必须复用 codec。`ResponseBody` 在成功、repository mismatch、静态校验失败和中途 IOException 后都必须关闭。

- [ ] **Step 2: 为新增 manifest/path 校验写失败测试**

  分别覆盖 depth 25、`requires` 非数组、非字符串元素、非空 module、`i18n` 非 object、空 path、逃逸 path、缺失文件；空 `requires` 与合法 locale 文件必须接受。

- [ ] **Step 3: 为 transport IOException 写失败测试**

  使用自定义 `ResponseBody`/`Source` 在读取 ZIP 中途抛 `IOException`，断言抛出 `ExtensionSourceException` 子类，而不是 `InvalidExtensionArchiveException`。

- [ ] **Step 4: 运行 RED**

  Run: `cd mobile; .\gradlew.bat testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.ExtensionArchiveInstallerTest" --tests "com.stapk.mobile.nativeadapter.ExtensionRecordCodecTest" --tests "com.stapk.mobile.nativeadapter.ExtensionRegistryTest" --tests "com.stapk.mobile.nativeadapter.GitHubExtensionClientTest" --rerun-tasks`

  Expected: 因 `prepare()`/sidecar/depth/requires/i18n/transport 分类尚未实现而 FAIL。

- [ ] **Step 5: 实现最小 prepare 与校验**

  `prepare()` 仅解压、校验、写 sidecar并返回 staging；不读取、移动或删除正式 target。相对 path 段数用 `segments.drop(1).size <= 24`；保留 sidecar 名在解压目标计算前拒绝。`requires` 和 `i18n` 使用 Gson 类型检查，所有路径通过 `SafePath.child()`。`ExtensionRegistry` 的 record JSON 解析与序列化改为复用 `ExtensionRecordCodec`，不得保留第二套 required-field 逻辑。把原 private `moveDirectory` 提取为 `ExtensionDirectoryOperations.kt` 的包内原子 move 函数。为保持中间状态可编译，旧 `install()` 改成明确标记 `@Deprecated` 的薄 wrapper：只调用 `prepare()`，随后执行原 activation/rollback；不得复制 extraction、manifest 或 sidecar 逻辑。

- [ ] **Step 6: 区分传输失败和 ZIP 失败**

  新增 `ExtensionArchiveTransportException : ExtensionSourceException`。仅 archive body 读取产生的原始 `IOException` 映射为该异常；`ZipException`、格式和静态校验继续映射 422。

- [ ] **Step 7: 运行 GREEN**

  Run: 与 Step 4 相同。

  Expected: focused JVM tests PASS，staging 在成功 close 与所有失败路径均无残留。

- [ ] **Step 8: 记录任务结果**

  更新 ledger，不提交。

---

### Task 3: 实现 registry replaceAll、journal 与 quarantine 原语

**Files:**
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeAdapterPaths.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionRegistry.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionTransactionJournal.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionDirectoryQuarantine.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionModels.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/DiagnosticLogger.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ExtensionRegistryTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ExtensionTransactionJournalTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ExtensionDirectoryQuarantineTest.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/DiagnosticLoggerTest.kt`

**Interfaces:**
- Produces:

  ```kotlin
  enum class ExtensionOperation { INSTALL, UPDATE, DELETE }
  enum class ExtensionTransactionPhase { PREPARED, FILES_ACTIVATED, REGISTRY_COMMITTED }
  data class ExtensionTransaction(
      val schemaVersion: Int = 1,
      val transactionId: String,
      val operation: ExtensionOperation,
      val phase: ExtensionTransactionPhase,
      val folderName: String,
      val oldRecord: ExtensionRecord?,
      val newRecord: ExtensionRecord?,
      val stagingName: String?,
      val backupName: String?,
      val trashName: String?
  )
  class ExtensionTransactionJournal(
      private val paths: NativeAdapterPaths,
      private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir),
      private val fileRemover: (File) -> Boolean = File::delete
  ) {
      fun read(): ExtensionTransaction?
      fun write(transaction: ExtensionTransaction)
      fun clear()
  }
  class ExtensionDirectoryQuarantine(
      private val paths: NativeAdapterPaths,
      private val clock: () -> Long = System::currentTimeMillis,
      private val uuid: () -> UUID = UUID::randomUUID,
      private val directoryMover: (File, File) -> Unit = ::moveDirectoryAtomically
  ) {
      fun move(source: File, reason: String, operation: String): File
  }
  fun ExtensionRegistry.replaceAll(records: List<ExtensionRecord>)
  ```

- Produces paths: `state/extension-transaction.json` 和 `quarantine/extensions/{timestamp}-{uuid}/{originalName}`。

- [ ] **Step 1: 写 journal schema 与 SafePath 失败测试**

  覆盖 schemaVersion=1 round trip；wire JSON 的 operation/phase 必须严格为 lowercase `install|update|delete` 和 `prepared|files_activated|registry_committed`；拒绝未知 schema/operation/phase、绝对或逃逸 basename、损坏 JSON；`clear()` 幂等且可注入删除失败。

  operation 交叉约束必须逐项测试：install 仅允许 `oldRecord=null,newRecord+stagingName`；update 必须有 old/new/staging/backup 且无 trash；delete 必须有 old/trash 且无 new/staging/backup。所有 record.folderName 必须等于 transaction.folderName；UUID 必须规范；非空目录名必须精确等于 `.stapk-txn-{transactionId}.installing|backup|trash`，不能接受任意安全 basename。

- [ ] **Step 2: 写 quarantine 与 replaceAll 失败测试**

  断言目录内容完整移动；`diagnostic.json` 只含 reason/source/operation/timestamp；`replaceAll` 拒绝 folder/repository 冲突并只产生一次原子 registry 写。

- [ ] **Step 3: 写 sidecar 严格 codec 与 diagnostics allowlist 失败测试**

  `ExtensionRecordCodec` 拒绝缺字段、错类型、额外字段和非法 folder；sidecar record 与所在 target basename 的绑定在 recovery 中验证。Diagnostics 保留现有 HTTP/storage/provider allowlist，并仅为 extension transaction 增加 `operation, phase, folder, result, errorClass, recoveredCount, quarantinedCount`；传入 response body、manifest、API key 字段必须被丢弃。

- [ ] **Step 4: 运行 RED**

  Run: `cd mobile; .\gradlew.bat testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.ExtensionRegistryTest" --tests "com.stapk.mobile.nativeadapter.ExtensionRecordCodecTest" --tests "com.stapk.mobile.nativeadapter.ExtensionTransactionJournalTest" --tests "com.stapk.mobile.nativeadapter.ExtensionDirectoryQuarantineTest" --tests "com.stapk.mobile.nativeadapter.DiagnosticLoggerTest" --rerun-tasks`

  Expected: 新类型和接口不存在而 FAIL。

- [ ] **Step 5: 实现严格 journal codec**

  使用显式 codec 输出 lowercase wire 值并完整执行 operation/record/UUID/目录名交叉校验。JSON 只保存 basename；损坏或语义非法文件调用现有 `AtomicFileStore.quarantine(..., "invalid_extension_transaction")`。`clear()` 只在 journal 存在时调用 `fileRemover`，false 时抛出可观察异常。

- [ ] **Step 6: 实现 quarantine、replaceAll 与 diagnostics allowlist**

  quarantine 使用同文件系统 move；目的 batch 写诊断元数据。`replaceAll()` 在单一 synchronized 方法中验证并调用一次 `writeRecords()`。

- [ ] **Step 7: 运行 GREEN**

  Run: 与 Step 4 相同。

  Expected: focused tests PASS。

- [ ] **Step 8: 记录任务结果**

  更新 ledger，不提交。

---

### Task 4: 用单一协调器实现 install/update/delete 事务

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionMutationCoordinator.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionArchiveInstaller.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionController.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ExtensionMutationCoordinatorTest.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ExtensionControllerTest.kt`

**Interfaces:**
- Consumes: `PreparedExtension`、journal、quarantine、registry。
- Produces:

  ```kotlin
  class ExtensionMutationCoordinator(
      private val paths: NativeAdapterPaths,
      private val registry: ExtensionRegistry,
      private val journal: ExtensionTransactionJournal,
      private val quarantine: ExtensionDirectoryQuarantine,
      private val diagnosticLogger: DiagnosticLogger? = null,
      private val uuid: () -> UUID = UUID::randomUUID,
      private val directoryMover: (File, File) -> Unit = ::moveDirectoryAtomically,
      private val directoryRemover: (File) -> Boolean = File::deleteRecursively
  ) {
      fun install(prepared: PreparedExtension): ExtensionRecord
      fun update(expected: ExtensionRecord, prepared: PreparedExtension): ExtensionRecord
      fun delete(expected: ExtensionRecord): Boolean
      fun <T> underLock(block: () -> T): T
      fun setRecoveryReady(ready: Boolean)
  }
  ```

- Produces domain exceptions，Controller 必须一对一映射，禁止 catch 通用 `Exception`/`IOException` 推断：

  ```text
  ExtensionAlreadyInstalledException -> 409 extension_already_installed
  ExtensionOperationConflictException -> 409 extension_operation_conflict
  InvalidExtensionArchiveException -> 422 invalid_extension_archive
  ExtensionArchiveTransportException -> 502 extension_source_unavailable
  ExtensionRegistryWriteException -> 500 extension_registry_write_failed
  ExtensionTransactionException -> 500 extension_transaction_failed
  ExtensionRecoveryRequiredException -> 503 extension_recovery_required
  ```

- [ ] **Step 1: 写旧空目录和孤儿目录重试的失败测试**

  预置空 target 与含文件 target；install 后新 target 有新 sidecar，旧内容完整存在 quarantine，registry 与 target 一致。

- [ ] **Step 2: 写并发安装失败测试**

  使用 `CountDownLatch` barrier 让两个 prepared install 同时进入；只允许一个成功，另一个抛 conflict，成功 target 和 registry 不得被删除，两个 staging 都清理。

- [ ] **Step 3: 写 registry failure 回滚矩阵**

  注入在 install/update/remove 写入时失败的 store。install 恢复到无记录/无新 target；update 恢复 old target 与 old record；delete 恢复 trash 到 target 与 old record。随后同操作可直接重试成功。

- [ ] **Step 4: 写 journal、activation、rollback 和 cleanup failure 矩阵**

  对 install/update/delete 分别注入 `journal.write(prepared)`、`journal.write(files_activated)`、`journal.write(registry_committed)` 失败；注入 target/backup/trash activation move 失败、rollback restore move 失败和 `journal.clear()` 失败。每个 registry commit 前可完整回滚的 journal/目录失败返回 `500 extension_transaction_failed`，registry 写失败返回 `500 extension_registry_write_failed`，且随后直接重试成功；rollback 不完整或保留不一致状态返回 `503 extension_recovery_required`。registry 已提交后的 backup/trash 删除或 journal clear 失败仍返回成功，但 coordinator 立刻 `ready=false`，保留 journal，后续 mutation 必须返回 503，不得覆盖单一 journal。

  prepare 后改变 registry commit，update 必须 409 且不激活 staging。Controller 另加端到端测试：archive body 中途 `IOException` 必须返回 `502 {"error":"extension_source_unavailable"}`。

- [ ] **Step 5: 运行 RED**

  Run: `cd mobile; .\gradlew.bat testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.ExtensionMutationCoordinatorTest" --tests "com.stapk.mobile.nativeadapter.ExtensionControllerTest" --rerun-tasks`

  Expected: coordinator 不存在，或现有 Controller 在并发/注入失败下破坏一致性。

- [ ] **Step 6: 实现全局锁与三种状态机**

  source resolve 与 `prepare()` 保持锁外；锁外只允许读取 `@Volatile ready` 做快速 503。进入全局锁后再次检查 ready，再读取 journal、registry 与 target；只有锁内发现前一事务遗留 journal 才返回 503。正常并发请求必须等待持锁事务完成，再根据新 registry 返回 409，不得因在途 journal 误报 503。每次目录 move 前后按规格写 `prepared -> files_activated -> registry_committed`。registry commit 前失败必须回滚；commit 后收尾失败保留 journal、置 `ready=false` 并返回成功。

- [ ] **Step 7: 将 Controller mutation 委托给 coordinator**

  Controller 负责 request/source/上述 typed HTTP mapping，不再直接移动/删除目录或写 registry。所有 `PreparedExtension` 使用 `use`，确保冲突和失败清 staging。同一步删除 Task 2 临时保留的 deprecated `install()` wrapper 和 legacy activation；Task 4 完成后 production code 不得再调用或声明该接口。

- [ ] **Step 8: 运行 GREEN**

  Run: 与 Step 5 相同。

  Expected: 并发、rollback、直接重试和 HTTP mapping tests PASS。

- [ ] **Step 9: 记录任务结果**

  更新 ledger，不提交。

---

### Task 5: 实现启动恢复矩阵与 mutation readiness

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionRecovery.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionSubsystem.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionMutationCoordinator.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ExtensionRecoveryTest.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ExtensionControllerTest.kt`

**Interfaces:**
- Produces:

  ```kotlin
  data class ExtensionRecoveryResult(
      val ready: Boolean,
      val recoveredOperations: Int,
      val quarantinedDirectories: Int
  )

  class ExtensionRecovery(
      private val paths: NativeAdapterPaths,
      private val registry: ExtensionRegistry,
      private val journal: ExtensionTransactionJournal,
      private val quarantine: ExtensionDirectoryQuarantine,
      private val coordinator: ExtensionMutationCoordinator,
      private val diagnosticLogger: DiagnosticLogger? = null,
      private val directoryMover: (File, File) -> Unit = ::moveDirectoryAtomically,
      private val directoryRemover: (File) -> Boolean = File::deleteRecursively
  ) {
      fun recover(): ExtensionRecoveryResult
  }

  internal interface ExtensionRoutes {
      fun discover(): HttpResponse
      fun install(body: String): HttpResponse
      fun version(body: String): HttpResponse
      fun update(body: String): HttpResponse
      fun delete(body: String): HttpResponse
  }

  internal data class ExtensionSubsystem(
      val routes: ExtensionRoutes,
      val recoveryResult: ExtensionRecoveryResult
  )

  internal fun createExtensionSubsystem(
      paths: NativeAdapterPaths,
      store: AtomicFileStore,
      diagnosticLogger: DiagnosticLogger
  ): ExtensionSubsystem
  ```

- Native server 必须在注册 extension routes 前同步调用 `recover()`；`ready=false` 时 mutation 返回 `503 {"error":"extension_recovery_required"}`。

- [ ] **Step 1: 按 journal operation/registry/disk 三元组写参数化失败测试**

  覆盖规格矩阵中的 install、update、delete commit 前后状态，包括：journal=prepared 且文件动作未开始；update 只完成 target->backup；文件动作完成但 files_activated 尚未写；registry 已提交但 registry_committed 尚未写；post-commit cleanup 未完成。无法唯一判定或出现矛盾组合时必须 fail closed：不删除、不覆盖、保留 journal并返回 `ready=false`。

- [ ] **Step 2: 写无 journal reconciliation 失败测试**

  覆盖 sidecar 重建 registry、缺 sidecar target quarantine、registry target 缺失、旧 `.folder.previous` 恢复、stale `.stapk-txn-*.installing` 删除、孤立 backup/trash quarantine。Sidecar 矩阵必须固定以下动作：已注册 target 的 sidecar 缺失/损坏/不匹配时，以 registry 为权威，保留性隔离无效 sidecar entry 后重写并保留 target；未注册 target 的 sidecar 缺失/损坏/非普通文件/basename 不匹配时 quarantine 整个 target；未注册有效 sidecar 与已注册 record 冲突时 quarantine 未注册 target；多个未注册有效 sidecar 互相发生 folder/repository 冲突时 quarantine 所有冲突 target，不选择胜者。只有严格 codec 有效、basename 绑定且无冲突的剩余 sidecar 可一次性重建 registry。

- [ ] **Step 3: 写损坏 registry/journal、server 构造顺序与 503 测试**

  损坏文件先 quarantine；可由 sidecar安全重建则 `ready=true`，无法完成目录 move/registry write 则 `ready=false`。为 `NativeHttpServer` 增加 internal `extensionSubsystemFactory` 构造参数（默认 `::createExtensionSubsystem`），测试 factory/recovery 在 `registerRoutes` 前同步完成；使用真实 Controller/routes 断言 ready=false 时 discover=200，version 保持其原有 200/404/502 合同，而 install/update/delete 返回 503。Factory fake 只用于证明构造顺序，不用于替代 HTTP 合同断言。

- [ ] **Step 4: 运行 RED**

  Run: `cd mobile; .\gradlew.bat testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.ExtensionRecoveryTest" --tests "com.stapk.mobile.nativeadapter.ExtensionControllerTest" --rerun-tasks`

  Expected: recovery 不存在或 server 未执行恢复而 FAIL。

- [ ] **Step 5: 实现 journal-aware recovery**

  在 coordinator 的同一锁内先比较 registry 与 old/new record，再决定 rollback/commit cleanup；不得只依赖 phase。所有动作幂等，第二次 `recover()` 返回 ready 且不重复 quarantine。任何未识别/矛盾状态保持原始证据并 fail closed，不进行猜测性删除或 move。

- [ ] **Step 6: 实现无 journal reconciliation 和 legacy previous 迁移**

  扫描只接受严格命名；未知隐藏目录不猜测。registry 重建必须汇总记录后一次 `replaceAll()`。

- [ ] **Step 7: 接入 NativeHttpServer 和 diagnostics**

  `createExtensionSubsystem()` 构造共享 registry/journal/coordinator/recovery，先同步 `recover()` 再返回 routes；NativeHttpServer 只依赖 `ExtensionSubsystem.routes` 注册端点。记录恢复数量与 quarantine 数量，失败只禁用 mutation endpoints。

- [ ] **Step 8: 运行 GREEN**

  Run: 与 Step 4 相同。

  Expected: crash-window 与 readiness tests PASS。

- [ ] **Step 9: 记录任务结果**

  更新 ledger，不提交。

---

### Task 6: 修正前端删除反馈并建立 capability/UI 漂移合同

**Files:**
- Modify: `patches/sillytavern-no-node/0009-stapk-mobile-extension-and-import-compatibility.patch`
- Modify: `test/no-node/task10-extension-import-compatibility.test.mjs`
- Modify: `test/no-node/mobile-implemented-ui-visibility.test.mjs`
- Regenerate: `mobile/app/src/main/assets/sillytavern-web/scripts/extensions.js`
- Regenerate: `mobile/app/src/main/assets/sillytavern-web/css/stapk-mobile.css`
- Regenerate: `mobile/app/src/main/assets/stapk-web-manifest.json`
- Regenerate: `mobile/app/src/main/assets/transform-report.json`

**Interfaces:**
- Produces: `deleteExtension()` 只在 `response.ok` 时 toast 成功并 reload；失败显示 `Extension delete failed`。
- Produces: implemented UI action 清单与 unsupported-hidden selector 清单的结构化 verifier。

- [ ] **Step 1: 写删除失败行为的 RED 测试**

  在 JS fixture 中 mock fetch 返回 500，断言无成功 toast、无 reload、有失败提示；200 保持现有成功行为。

- [ ] **Step 2: 扩展可见性合同 RED 测试**

  显式清单至少包含 Streaming、扩展安装、详情、手动更新、删除、更新通知、World Info；解析最终 HTML 祖先 class 与 CSS selector。所有隐藏 selector 必须映射到 `unsupported_hidden` capability 及理由。

- [ ] **Step 3: 运行 RED**

  Run: `node --test test/no-node/task10-extension-import-compatibility.test.mjs test/no-node/mobile-implemented-ui-visibility.test.mjs`

  Expected: 当前 `deleteExtension()` 未检查 HTTP status。

- [ ] **Step 4: 修改 patch 并重新生成 assets**

  使用：

  ```js
  const response = await fetch('/api/extensions/delete', options);
  if (!response.ok) {
      throw new Error('Extension delete failed');
  }
  ```

  catch 分支只显示失败 toast；成功 toast 和 reload 保留在成功路径。

- [ ] **Step 5: 运行 GREEN 与完整 no-node tests**

  Run: `npm run transform:no-node`

  Run: `npm run test:no-node`

  Expected: 全部 no-node tests PASS，正式 assets 与 patch queue 一致。

- [ ] **Step 6: 记录任务结果**

  更新 ledger，不提交。

---

### Task 7: 全量验证、模拟器验收与最终审查

**Files:**
- Modify: `docs/superpowers/specs/2026-07-21-stapk-extension-transaction-recovery-design.md`（仅当实现中的已确认接口需要同步）
- Modify: `.superpowers/sdd/progress.md`（git ignored）
- Regenerate: `mobile/app/src/main/assets/*`（仅通过正式构建命令）
- Output: `output/stapk-mobile-debug.apk`

**Interfaces:**
- Consumes: Tasks 1-6 的所有产物。
- Produces: 可复现的测试、APK checksum、模拟器证据和全分支 review 结论。

- [ ] **Step 1: 运行 focused extension 与 streaming JVM tests**

  Run: `cd mobile; .\gradlew.bat testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.*Extension*Test" --tests "com.stapk.mobile.nativeadapter.*Streaming*Test" --rerun-tasks`

  Expected: 0 failures。

- [ ] **Step 2: 运行完整 Android JVM suite**

  Run: `cd mobile; .\gradlew.bat testDebugUnitTest --rerun-tasks`

  Expected: 0 failures；允许已记录的 Windows 文件系统平台 skips：3 项 symlink
  场景，以及 `PersonaControllerTest` 中 Windows 无法创建含双引号文件名的 1 项场景。

- [ ] **Step 3: 运行 no-node 与严格 verifiers**

  Run: `npm run test:no-node`

  Run: `npm run transform:no-node:verify`

  Run: `npm run verify:no-node-capabilities`

  Expected: 全部退出码 0，`needs_review=0`。

- [ ] **Step 4: 一键构建 Debug APK**

  Run: `npm run build:no-node-apk -- --variant debug --ref release`

  Expected: APK 与 5 个配套产物写入 `output/`，构建退出码 0。

- [ ] **Step 5: 验证 diff 与 no-node 边界**

  Run: `git diff --check`

  Run: `git status --short`

  Expected: 无 whitespace error；没有 runtime Node archive、日志、临时 transaction 或 quarantine 数据进入版本控制。

- [ ] **Step 6: Pixel 8 / API 35 模拟器验收**

  安装最新 Debug APK，验证：Streaming 开关可见；preset `true|false|missing|invalid` 导入与重启；扩展更新通知可见且默认关闭；安装、更新、删除；预置空目录/孤儿目录后无需手工清理即可重试；mutation 中断后重启自动恢复。收集 diagnostics，确认无密钥、正文或 manifest 泄漏。

- [ ] **Step 7: 最终 whole-branch review**

  reviewer 对照设计第 2、4、5、6、7、8、9、10、11、12、13 节，报告 Critical/Important/Minor、测试缺口和 Ready-to-merge verdict。Critical/Important 必须修复并复审。

- [ ] **Step 8: 更新 ledger 并交付**

  记录全量测试计数、skip、APK SHA-256、模拟器设备 serial、验收结果和 review 结论；不提交、不推送、不发版。
