# stAPK 远程 Embedding 与本地 SQLite 向量存储 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Android no-node 版 stAPK 中恢复 SillyTavern 官方 Vector Storage UI，使 Data Bank、聊天记忆和 World Info 通过远程 OpenAI-compatible embedding 与本地 framework SQLite 完成持久化和精确 Top-K 检索。

**Architecture:** SillyTavern 前端继续负责切块、同步和 Prompt 注入；`VectorController` 兼容七个上游 `/api/vector/*` 契约；`EmbeddingProviderClient` 仅调用已保存的 OpenAI 或 Custom OpenAI-compatible `/embeddings`；`VectorRepository` 使用单一 `SQLiteOpenHelper` 实例存储归一化 Float32 Little Endian BLOB，查询时 Cursor 流式扫描并维护有界 Top-K 小根堆。`NativeHttpService` 是整个向量子系统的唯一生命周期 owner。

**Tech Stack:** Kotlin/JVM 17、Android framework SQLite/`SQLiteOpenHelper`、OkHttp 4.12、Gson 2.14、NanoHTTPD、JUnit 4、Robolectric 4.12、MockWebServer、Node.js 仅用于构建时 transform/contract 测试。

## 进度状态（2026-07-31）

- Task 1–10 已按当前工作区实现和自动化证据回填完成。
- Task 11 已完成证据模板、API 35 全链路、隐私扫描和最终自动化门禁；项目正式维护范围已经收敛为 API 35 及以上。
- 复选框按当前可验证交付结果回填；历史 Red 阶段的原始终端日志不作为独立发布证据保留。
- 当前重新构建的 Debug APK SHA-256：`6beacb5e307760516a275fd79d5a90739393bda076572fe2d57ccd0780b9d602`；相同实现链的前一候选 `996fc5d6...e1c1e530` 保留完整远程 Embedding/RAG 与 instrumentation 证据。
- API 24–34 不纳入支持矩阵，不再阻断“全部实施并验收”的最终状态收口。

## Global Constraints

- [x] 实施前重读 `CLAUDE.md`、当前权威架构 `docs/superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md` 和本功能规格 `docs/superpowers/specs/2026-07-30-stapk-remote-embedding-local-vector-design.md`。
- [x] 严守 APK/runtime 无 Node.js 约束：不引入 Node runtime、本地 embedding 模型、Python、ONNX Runtime、FAISS、HNSW、SQLite native vector extension 或新 ABI 库。
- [x] 不修改 `build/stapk-no-node/upstream/`、`build/no-node-payload/` 或 `mobile/app/src/main/assets/sillytavern-web/` 中的生成文件；前端变更必须进入 fixed patch queue/transform source，然后重新生成 assets。
- [x] 不实现 0.2.x → 当前版本迁移，不把完整备份/恢复、远程 TTS 或新文件解析器带入本计划。
- [x] `vector-store.db` 是可重建派生索引；不将其当作 canonical data。当前 Manifest 已设 `android:allowBackup="false"`，本计划不改 Android Auto Backup 策略。
- [x] 每个任务严格按 Red → Green → Refactor 执行；先运行新增精准测试并确认以预期原因失败，再写实现。
- [x] 不自行执行 `git commit` 或 `git push`。各任务末尾只给出“建议提交”，由 Master 手动触发。
- [x] 保留用户已有 dirty worktree 变更；实施每个任务前用 `git status --short` 重新确认范围。

## Shared Contracts

以下类型和线上字段在所有任务中保持一致，不在后续任务中另起一套名称：

```kotlin
enum class EmbeddingProviderType(val wireName: String, val sourceId: String, val secretKey: String) {
    OPENAI("openai", "openai", "api_key_openai"),
    STAPK_OPENAI_COMPATIBLE(
        "stapk_openai_compatible",
        "stapk_openai_compatible",
        "api_key_embedding"
    )
}

data class EmbeddingProviderConfig(
    val type: EmbeddingProviderType,
    val baseUrl: String,
    val model: String
)

data class EmbeddingProviderSnapshot(
    val config: EmbeddingProviderConfig,
    val apiKey: String,
    val normalizedBaseUrl: String,
    val endpointFingerprint: String,
    val modelFingerprint: String
)

data class VectorItemInput(val hash: Long, val text: String, val index: Int)
data class EncodedVector(val dimension: Int, val blob: ByteArray)
data class VectorMetadata(val hash: Long, val text: String, val index: Int)
data class VectorQueryResult(val hashes: List<Long>, val metadata: List<VectorMetadata>)

class EmbeddingFailure(
    val httpStatus: Int,
    val errorCode: String,
    cause: Throwable? = null
) : RuntimeException(errorCode, cause)
```

配置 API 契约：

```json
POST /api/stapk/embeddings/config/get
{}
=> {"type":"openai","baseUrl":"https://api.openai.com/v1","model":"text-embedding-3-small","keyConfigured":true}

POST /api/stapk/embeddings/config/save
{"type":"stapk_openai_compatible","baseUrl":"https://example.com/v1","model":"embed-model","apiKey":"optional-new-value"}
=> {"type":"stapk_openai_compatible","baseUrl":"https://example.com/v1","model":"embed-model","keyConfigured":true}

POST /api/stapk/embeddings/test
{}
=> {"ok":true,"dimension":1536}
```

`config/get` 和 `config/save` 绝不返回 key value；`apiKey` 在 save 中缺省表示保留原值，空字符串表示删除当前 Custom key。OpenAI 类型忽略 save body 中的 `baseUrl`，始终强制 `https://api.openai.com/v1`。

---

### Task 1: 锁定 Provider 配置、Secret 和日志契约

**Files:**

- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/vector/EmbeddingProviderConfigStore.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/vector/EmbeddingFailure.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/vector/EmbeddingProviderConfigStoreTest.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeAdapterPaths.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/SecretStore.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/DiagnosticLogger.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/SecretStoreTest.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/DiagnosticLoggerTest.kt`

**Interfaces:**

```kotlin
class EmbeddingProviderConfigStore(
    private val paths: NativeAdapterPaths,
    private val secretStore: SecretStore,
    private val atomicStore: AtomicFileStore
) {
    fun load(): EmbeddingProviderConfig
    fun save(config: EmbeddingProviderConfig, apiKeyUpdate: String?): EmbeddingProviderConfig
    fun snapshot(): EmbeddingProviderSnapshot
    fun keyConfigured(type: EmbeddingProviderType): Boolean
}
```

- [x] **Step 1: 先写 ConfigStore 失败测试**

覆盖默认 OpenAI 配置、Custom HTTPS、loopback HTTP、拒绝远程 HTTP/user-info/query/fragment、去除末尾 `/`、Base URL 2048 上限、model 256 上限、SHA-256 fingerprint，以及 malformed JSON 隔离后返回 `vector_invalid_request`（不静默回落到默认 Provider）。

```kotlin
@Test
fun `custom provider rejects insecure remote http`() {
    val error = assertThrows(IllegalArgumentException::class.java) {
        store.save(
            EmbeddingProviderConfig(
                EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE,
                "http://example.com/v1",
                "embed-model"
            ),
            "secret"
        )
    }
    assertEquals("embedding_base_url_invalid", error.message)
}

@Test
fun `loopback http is normalized and accepted`() {
    val saved = store.save(
        EmbeddingProviderConfig(
            EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE,
            "http://127.0.0.1:8080/v1/",
            "embed-model"
        ),
        "secret"
    )
    assertEquals("http://127.0.0.1:8080/v1", saved.baseUrl)
}
```

- [x] **Step 2: 运行精准测试并确认 Red**

```powershell
.\mobile\gradlew.bat --no-daemon -p mobile :app:testDebugUnitTest --tests '*EmbeddingProviderConfigStoreTest'
```

Expected: 因 `EmbeddingProviderConfigStore`/新 path 尚不存在而编译失败。

- [x] **Step 3: 实现配置文件与不可变 snapshot**

在 `NativeAdapterPaths` 新增：

```kotlin
val embeddingProviderConfigFile: File = File(userConfigDir, "embedding-provider.json")
```

ConfigStore 用 `AtomicFileStore` 写 JSON；用 `URI` 解析 URL；只允许 `https`，或 host 为 `127.0.0.1`/`localhost`/`::1` 的 `http`；拒绝 user-info/query/fragment。`snapshot()` 在一次调用中完成 config + secret 复制，缺 key 抛 `EmbeddingFailure(401, "embedding_key_missing")`。

- [x] **Step 4: 先扩展 SecretStore 测试，再加 `api_key_embedding`**

```kotlin
@Test
fun `embedding key is supported and always redacted in read state`() {
    store.write("api_key_embedding", "top-secret", "Embedding")
    assertEquals("top-secret", store.load("api_key_embedding")?.value)
    val state = JsonParser.parseString(store.readStateJson()).asJsonObject
    assertEquals("********", state["api_key_embedding"].asJsonArray[0].asJsonObject["value"].asString)
    assertFalse(store.readStateJson().contains("top-secret"))
}
```

只将 `api_key_embedding` 加入 `SUPPORTED_KEYS`，不改现有 secret 文件格式。

- [x] **Step 5: 扩展诊断日志白名单并测试泄漏边界**

新增 `DiagnosticArea.VECTOR`；允许 `batchCount`/`dimension`/`itemCount`/`databaseBytes` 为非负整数，允许 `collectionSha256`/`modelSha256` 为 64 位小写 hex。不将 `text`、`prompt`、`vector`、`apiKey`、`authorization`、`baseUrl` 放入白名单。

```kotlin
logger.event(
    DiagnosticArea.VECTOR,
    "vector_query_failed",
    mapOf(
        "host" to "example.com",
        "dimension" to "1536",
        "collectionSha256" to "a".repeat(64),
        "text" to "private chunk",
        "baseUrl" to "https://example.com/v1?token=secret"
    )
)
assertFalse(logText.contains("private chunk"))
assertFalse(logText.contains("token=secret"))
assertTrue(logText.contains("\"dimension\":\"1536\""))
```

- [x] **Step 6: 运行 Task 1 测试**

```powershell
.\mobile\gradlew.bat --no-daemon -p mobile :app:testDebugUnitTest --tests '*EmbeddingProviderConfigStoreTest' --tests '*SecretStoreTest' --tests '*DiagnosticLoggerTest'
```

Expected: PASS。

**建议提交（由 Master 手动执行）：** `feat: 添加 Embedding Provider 配置与密钥基础`

---

### Task 2: 实现向量编解码与 OpenAI-compatible Provider Client

**Files:**

- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/vector/VectorCodec.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/vector/EmbeddingProviderClient.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/vector/VectorCodecTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/vector/EmbeddingProviderClientTest.kt`

**Interfaces:**

```kotlin
data class EmbeddingBatch(val vectors: List<FloatArray>, val dimension: Int)

object VectorCodec {
    const val MAX_DIMENSION = 32768
    fun normalize(vector: FloatArray): FloatArray
    fun encodeNormalized(vector: FloatArray): EncodedVector
    fun decode(blob: ByteArray, expectedDimension: Int): FloatArray
    fun dot(left: FloatArray, right: FloatArray): Float
}

class EmbeddingProviderClient(
    private val httpClient: OkHttpClient,
    private val diagnosticLogger: DiagnosticLogger,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun embed(snapshot: EmbeddingProviderSnapshot, inputs: List<String>): EmbeddingBatch
}
```

- [x] **Step 1: 先写 VectorCodec 维度与非法值测试**

对 `384, 768, 1024, 1536, 3072, 32768` 轮询 round-trip，断言 BLOB 长度等于 `dimension * 4`，ByteBuffer 序为 `LITTLE_ENDIAN`，归一化后范数误差 `<= 1e-5`。拒绝空向量、NaN、正负无穷、零范数、32769 维、BLOB 长度不是 4 的倍数或与 expected dimension 不符。

- [x] **Step 2: 运行 Codec 测试确认 Red，然后最小实现**

```powershell
.\mobile\gradlew.bat --no-daemon -p mobile :app:testDebugUnitTest --tests '*VectorCodecTest'
```

累加范数使用 `Double`，完成验证后新建 `FloatArray`归一化，不就地修改 Provider 返回的数组。

- [x] **Step 3: 先写 MockWebServer Provider 契约测试**

覆盖：

- request path 必须是规范化后的 `${baseUrl}/embeddings`；
- 测试密钥为 `test-key` 时 `Authorization: Bearer test-key`、JSON `model` 和 `input[]` 正确；
- response `data` 按 `index` 还原顺序；
- 数量不符、重复/越界 index、批内维度不同、非法 JSON、非法数值均返回 422/502 对应 `EmbeddingFailure`；
- 401/403 映射 `embedding_provider_error` 502，429 映射 429，5xx 映射 502，`SocketTimeoutException` 映射 504；
- 超过 `32 MiB` 在解析前停止读取并返回 413；
- MockWebServer 只收到一次请求，证明没有自动 retry。

```kotlin
@Test
fun `response is reordered by index and normalized`() {
    server.enqueue(jsonResponse("""
        {"data":[
          {"index":1,"embedding":[0,3,4]},
          {"index":0,"embedding":[2,0,0]}
        ]}
    """))
    val result = client.embed(snapshot(server), listOf("first", "second"))
    assertArrayEquals(floatArrayOf(1f, 0f, 0f), result.vectors[0], 0.00001f)
    assertArrayEquals(floatArrayOf(0f, 0.6f, 0.8f), result.vectors[1], 0.00001f)
}
```

- [x] **Step 4: 实现有界读取、验证、映射与脱敏日志**

OkHttp client 统一使用 connect 15s、write 30s、read 60s；不开 retryOnConnectionFailure。用上限 `32 * 1024 * 1024 + 1` 的流式读取器，不相信 `Content-Length`。日志只记 host/status/durationMs/batchCount/dimension/errorClass。

- [x] **Step 5: 运行 Task 2 测试**

```powershell
.\mobile\gradlew.bat --no-daemon -p mobile :app:testDebugUnitTest --tests '*VectorCodecTest' --tests '*EmbeddingProviderClientTest'
```

Expected: PASS，且 MockWebServer `requestCount` 在错误场景为 1。

**建议提交（由 Master 手动执行）：** `feat: 实现远程 Embedding 客户端与向量编码`

---

### Task 3: 建立 SQLite schema 与向量写入/管理 Repository

**Files:**

- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/vector/VectorDatabaseHelper.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/vector/VectorRepository.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/vector/VectorDatabaseHelperTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/vector/VectorRepositoryMutationTest.kt`

**Interfaces:**

```kotlin
data class VectorNamespace(
    val collectionKey: String,
    val providerType: String,
    val endpointFingerprint: String,
    val model: String,
    val dimension: Int
)

class VectorDatabaseHelper(
    context: Context,
    private val paths: NativeAdapterPaths,
    private val diagnosticLogger: DiagnosticLogger,
    private val clock: () -> Long = System::currentTimeMillis
) : SQLiteOpenHelper(context.applicationContext, "vector-store.db", null, 1) {
    fun quarantineAndRecreate(cause: SQLiteDatabaseCorruptException): Nothing
}

class VectorRepository(
    private val helper: VectorDatabaseHelper,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun listHashes(namespace: VectorNamespace): List<Long>
    fun upsertBatch(namespace: VectorNamespace, items: List<VectorItemInput>, vectors: List<EncodedVector>)
    fun deleteHashes(namespace: VectorNamespace, hashes: List<Long>)
    fun purgeCollection(collectionKey: String)
    fun purgeAll()
}
```

- [x] **Step 1: 先写 schema/PRAGMA 失败测试**

Robolectric 下使用每个 test 独立 `RuntimeEnvironment.getApplication()` 和唯一 database name 的 internal test constructor（生产构造器仍固定 `vector-store.db`）。断言 `user_version=1`、`foreign_keys=1`、`journal_mode=wal`、两张表、唯一约束、FK cascade 和 dimension/BLOB check。

SQL 必须与规格一致，时间字段使用 epoch milliseconds：

```sql
UNIQUE (collection_key, provider_type, endpoint_fingerprint, model)
UNIQUE (collection_id, content_hash)
CHECK (dimension > 0 AND dimension <= 32768)
CHECK (length(vector_blob) > 0 AND length(vector_blob) % 4 = 0)
```

- [x] **Step 2: 运行 Helper 测试确认 Red，再实现 schema**

```powershell
.\mobile\gradlew.bat --no-daemon -p mobile :app:testDebugUnitTest --tests '*VectorDatabaseHelperTest'
```

`onConfigure` 开启 FK，`onCreate` 创建 schema，`onOpen` 执行 `PRAGMA synchronous=NORMAL`；不引入 Room。

- [x] **Step 3: 先写 Repository mutation 测试**

覆盖 create-on-first-upsert、同 hash upsert 替换 text/index/vector、list 稳定排序、幂等 delete、purge collection 删除该 collection 的所有 Provider/model namespace、purge-all 保留 schema。

```kotlin
@Test
fun `failed batch leaves no collection or partial item`() {
    val bad = EncodedVector(3, byteArrayOf(1, 2, 3, 4))
    assertThrows(IllegalArgumentException::class.java) {
        repository.upsertBatch(namespace(3), listOf(item(1), item(2)), listOf(vector3(), bad))
    }
    assertEquals(emptyList<Long>(), repository.listHashes(namespace(3)))
}
```

- [x] **Step 4: 实现 transaction 内的 collection 核对和 batch upsert**

在打开 transaction 前验证 items/vectors 数量、维度、BLOB 长度、hash 唯一性。transaction 内：

1. `INSERT OR IGNORE` collection；
2. 查回 id/dimension；
3. dimension 不符抛 `EmbeddingFailure(409, "vector_dimension_changed")`；
4. 逐项先用 `SQLiteDatabase.update()` 按 `collection_id + content_hash` 更新，影响行数为 0 时再 `insertOrThrow()`；
5. 更新 collection `updated_at`；
6. 只在全部成功时 `setTransactionSuccessful()`。

继续保留不依赖现代 upsert 或 `RETURNING` 的保守 SQL；这是已经验证的稳定实现，但不再作为 API 24 设备验收门禁。

- [x] **Step 5: 添加 namespace 隔离测试**

同 collectionKey 分别写入不同 endpoint fingerprint/model，断言 list 互不可见；同 namespace 维度变化返回 409 且原数据不变。

- [x] **Step 6: 运行 Task 3 测试**

```powershell
.\mobile\gradlew.bat --no-daemon -p mobile :app:testDebugUnitTest --tests '*VectorDatabaseHelperTest' --tests '*VectorRepositoryMutationTest'
```

Expected: PASS。

**建议提交（由 Master 手动执行）：** `feat: 添加 SQLite 向量库与事务写入`

---

### Task 4: 实现 Cursor 流式精确检索、并发与损坏恢复

**Files:**

- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/vector/VectorDatabaseHelper.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/vector/VectorRepository.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/vector/VectorRepositoryQueryTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/vector/VectorRepositoryRecoveryTest.kt`

**Interfaces:**

```kotlin
data class VectorHit(
    val collectionKey: String,
    val score: Float,
    val metadata: VectorMetadata
)

fun VectorRepository.query(
    namespace: VectorNamespace,
    queryVector: FloatArray,
    topK: Int,
    threshold: Float
): VectorQueryResult

fun VectorRepository.queryMulti(
    namespaces: List<VectorNamespace>,
    queryVector: FloatArray,
    topK: Int,
    threshold: Float
): Map<String, VectorQueryResult>
```

- [x] **Step 1: 先写 query/multi-query 失败测试**

用手工可验证的单位向量覆盖：Top-K 降序、threshold、空 collection、负相似度、维度不符、multi 全局 Top-K 而不是每 collection Top-K、最终按 collectionId 分组。

固定 tie-break：`score DESC, collectionKey ASC, item_index ASC, content_hash ASC`，避免 SQLite 扫描顺序造成测试漂移。响应中 `hashes` 必须与 threshold 过滤后 `metadata` 一一对应（修复上游原 endpoint 先构造全部 hashes 的不一致行为）。

- [x] **Step 2: 运行精准测试确认 Red，再实现有界堆**

```powershell
.\mobile\gradlew.bat --no-daemon -p mobile :app:testDebugUnitTest --tests '*VectorRepositoryQueryTest'
```

查询 SQL 只取 `content_hash,item_index,text,vector_blob`；`Cursor.use` 内逐行 decode/dot；`PriorityQueue` 最多保留 `topK`；不建立“全部 VectorHit 列表”。multi 只创建一个全局堆。

- [x] **Step 3: 先写并发与 rollback 测试**

用 `CountDownLatch` + 两个 executor 制造 insert/purge 竞争，断言最终是完整新批次或完整空集，不存在半批。Provider 网络测试还需在 Task 5 证明 transaction 未在 HTTP 等待期持有。

- [x] **Step 4: 先写 DB 损坏隔离测试**

关闭 helper，将非 SQLite bytes 写入 `context.getDatabasePath("vector-store.db")`，同时创建 `-wal`/`-shm`；触发读操作后断言：

- 抛 `EmbeddingFailure(409, "vector_index_rebuild_required")`；
- DB/WAL/SHM 移入 `quarantine/vector-store-<timestamp>/`；
- 新 DB schema 可打开且 list 为空；
- 诊断日志不含原文本/BLOB。

- [x] **Step 5: 实现同步隔离与重建**

`quarantineAndRecreate` 使用 `@Synchronized`，先 `close()`，再将三个明确的绝对路径移入时间戳目录，调用 `writableDatabase` 创建空 schema，最后抛 rebuild-required。只对 `SQLiteDatabaseCorruptException`/明确 `SQLITE_CORRUPT` 进入此流程，不把磁盘满或普通约束错误当成损坏。

- [x] **Step 6: 运行 Task 4 测试**

```powershell
.\mobile\gradlew.bat --no-daemon -p mobile :app:testDebugUnitTest --tests '*VectorRepositoryQueryTest' --tests '*VectorRepositoryRecoveryTest'
```

Expected: PASS，并发测试连续运行 20 次不出现半写入。

**建议提交（由 Master 手动执行）：** `feat: 实现向量精确检索与损坏恢复`

---

### Task 5: 实现 VectorController 七个上游 API 和配置 API

**Files:**

- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/vector/VectorController.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/vector/VectorContracts.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/vector/EmbeddingProviderClient.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/vector/VectorRepository.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/vector/VectorControllerTest.kt`

**Interfaces:**

```kotlin
class VectorController(
    private val configStore: EmbeddingProviderConfigStore,
    private val providerClient: EmbeddingGateway,
    private val repository: VectorStore,
    private val usableSpace: () -> Long,
    private val diagnosticLogger: DiagnosticLogger
) {
    fun list(body: String): HttpResponse
    fun insert(body: String): HttpResponse
    fun delete(body: String): HttpResponse
    fun query(body: String): HttpResponse
    fun queryMulti(body: String): HttpResponse
    fun purge(body: String): HttpResponse
    fun purgeAll(): HttpResponse
    fun getConfig(): HttpResponse
    fun saveConfig(body: String): HttpResponse
    fun testConfig(): HttpResponse
}

interface VectorRoutes {
    fun list(body: String): HttpResponse
    fun insert(body: String): HttpResponse
    fun delete(body: String): HttpResponse
    fun query(body: String): HttpResponse
    fun queryMulti(body: String): HttpResponse
    fun purge(body: String): HttpResponse
    fun purgeAll(): HttpResponse
    fun getConfig(): HttpResponse
    fun saveConfig(body: String): HttpResponse
    fun testConfig(): HttpResponse
}

interface EmbeddingGateway {
    fun embed(snapshot: EmbeddingProviderSnapshot, inputs: List<String>): EmbeddingBatch
}

interface VectorStore {
    fun listHashes(namespace: VectorNamespace): List<Long>
    fun upsertBatch(namespace: VectorNamespace, items: List<VectorItemInput>, vectors: List<EncodedVector>)
    fun deleteHashes(namespace: VectorNamespace, hashes: List<Long>)
    fun purgeCollection(collectionKey: String)
    fun purgeAll()
    fun query(namespace: VectorNamespace, queryVector: FloatArray, topK: Int, threshold: Float): VectorQueryResult
    fun queryMulti(
        namespaces: List<VectorNamespace>,
        queryVector: FloatArray,
        topK: Int,
        threshold: Float
    ): Map<String, VectorQueryResult>
}
```

`EmbeddingProviderClient : EmbeddingGateway`、`VectorRepository : VectorStore`、`VectorController : VectorRoutes`；测试使用实现这些 interface 的 fake，不为了 mock 把生产类改成 `open`。

- [x] **Step 1: 先写上游 request/response contract 失败测试**

使用 fake client/repository（通过小型 interface `EmbeddingGateway`/`VectorStore`注入，生产类实现该 interface）覆盖七个 route shape。必须按上游契约断言：

```json
list => [123,456]
query => {"hashes":[123],"metadata":[{"hash":123,"text":"chunk","index":2}]}
query-multi => {"collection-a":{"hashes":[123],"metadata":[{"hash":123,"text":"chunk","index":2}]}}
insert/delete/purge/purge-all => HTTP 200
```

`purge` 只要 `collectionId`，不要求 source/model；`purge-all` 不要求 body。

- [x] **Step 2: 补全输入限制表的 parameterized tests**

- insert items `1..64`，空或 65 拒绝；
- text 最多 100000 chars；
- query-multi collections `1..64`；
- `topK 1..100`，`threshold 0.0..1.0` 且必须 finite；
- collectionId 非空、不得含控制字符、最大 512 chars；
- hash 必须是 JSON 整数并可无损转为 `Long`，index 必须是非负 `Int`；
- source 只允许当前 snapshot 的 `sourceId`，model 若出现则必须与 snapshot.model 一致；
- vector request 中的 `baseUrl`/`apiUrl`/`urlOverride`/`apiKey` 一律 400 fail closed。

为保持上游容错行为，query/query-multi 缺省 `topK` 时使用 10，缺省 `threshold` 时使用 0.0；显式传入的值仍必须通过上述边界。

- [x] **Step 3: 先写空间检查与事务边界测试**

Provider 前 `usableSpace < 64 MiB` 时断言 client 零调用。Provider 返回后计算：

```kotlin
val projectedBatchBytes = vectors.sumOf { it.blob.size.toLong() } +
    items.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() + 128L }
val required = maxOf(64L * 1024L * 1024L, projectedBatchBytes * 2L)
```

第二次空间不足时 repository 零调用，返 507。用 blocking fake client + latch 证明 HTTP 等待期 repository transaction 尚未开始。

- [x] **Step 4: 实现 insert/query 管线和统一 error mapper**

```kotlin
private fun failure(error: EmbeddingFailure): HttpResponse =
    HttpResponse.json(error.httpStatus, """{"error":"${error.errorCode}"}""")
```

不将 exception message/provider body/用户文本放入 error JSON。insert 整批 embed/encode 完成后才调 repository。query-multi 只调用一次 `embed(listOf(searchText))`。

- [x] **Step 5: 先写 config/get/save/test 测试，再实现**

`test` 只发送固定文本 `stAPK embedding connection test`，不写 DB。`save` 成功后返回脱敏 config view。验证 response 不含 API key，不含 Authorization。

- [x] **Step 6: 验证错误映射全表**

| HTTP | error |
|---:|---|
| 400 | `vector_invalid_request` |
| 401 | `embedding_key_missing` |
| 409 | `vector_dimension_changed` / `vector_index_rebuild_required` |
| 413 | `vector_request_too_large` |
| 422 | `embedding_invalid_vector` |
| 429 | `embedding_rate_limited` |
| 502 | `embedding_provider_error` |
| 504 | `embedding_timeout` |
| 507 | `vector_storage_full` |

- [x] **Step 7: 运行 Task 5 测试**

```powershell
.\mobile\gradlew.bat --no-daemon -p mobile :app:testDebugUnitTest --tests '*VectorControllerTest'
```

Expected: PASS。

**建议提交（由 Master 手动执行）：** `feat: 实现本地向量 API 与 Embedding 配置接口`

---

### Task 6: 接入 NativeHttpService 生命周期与真实 HTTP Router

**Files:**

- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/vector/VectorSubsystem.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpService.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/NativeRouterTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/vector/VectorHttpIntegrationTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/NativeHttpServiceTest.kt`

**Interfaces:**

```kotlin
class VectorSubsystem(
    context: Context,
    paths: NativeAdapterPaths,
    diagnosticLogger: DiagnosticLogger,
    httpClient: OkHttpClient = defaultEmbeddingHttpClient()
) : Closeable {
    val controller: VectorController
    override fun close()
}
```

`NativeHttpServer` 构造器扩展为：

```kotlin
constructor(
    paths: NativeAdapterPaths,
    port: Int = 0,
    exportStore: ExportStore = ExportStore(paths.exportsDir),
    diagnosticLogger: DiagnosticLogger = DiagnosticLogger(paths.logsDir),
    vectorRoutes: VectorRoutes? = null
)
```

`null` 只为保留现有纯 server unit tests 的轻量构造；生产 `NativeHttpService` 必须始终注入非空 routes。

- [x] **Step 1: 先写 Router 注册失败测试**

注入 fake `VectorRoutes`，断言以下 10 个 POST path 可 dispatch，GET 或错 path 返 404：

```text
/api/vector/list
/api/vector/insert
/api/vector/delete
/api/vector/query
/api/vector/query-multi
/api/vector/purge
/api/vector/purge-all
/api/stapk/embeddings/config/get
/api/stapk/embeddings/config/save
/api/stapk/embeddings/test
```

- [x] **Step 2: 在 `registerRoutes` 中只当 controller 非空时注册向量路由**

不让 `NativeHttpServer` 自己创建 SQLite helper；它只路由注入对象。

- [x] **Step 3: 先写 Service owner/close 失败测试**

将子系统创建抽成 internal factory，在测试中注入 recording fake，覆盖：成功启动只创建一次；停止时 server.stop 后 subsystem.close；server.start 失败时也 close；重复 stop 幂等。

- [x] **Step 4: 实现 Service 的唯一 owner**

```kotlin
private var vectorSubsystem: VectorSubsystem? = null

val diagnosticLogger = DiagnosticLogger(paths.logsDir)
val startedVectors = VectorSubsystem(applicationContext, paths, diagnosticLogger)
val startedServer = NativeHttpServer(
    paths = paths,
    diagnosticLogger = diagnosticLogger,
    vectorRoutes = startedVectors.controller
)
```

只在 server.start 成功后同时赋值两个字段。任一失败都按明确局部变量停止/关闭，不留孤儿 helper。

- [x] **Step 5: 写真实 NanoHTTPD + MockWebServer 集成测试**

启动 random local port，通过 HTTP 执行 config save → test → insert → list → query → delete → purge-all，停服务后重建 subsystem，确认未 purge 的索引可持久读取。

- [x] **Step 6: 运行 Task 6 测试**

```powershell
.\mobile\gradlew.bat --no-daemon -p mobile :app:testDebugUnitTest --tests '*NativeRouterTest' --tests '*NativeHttpServiceTest' --tests '*VectorHttpIntegrationTest'
```

Expected: PASS，测试结束后不留 server thread 或未关闭 DB。

**建议提交（由 Master 手动执行）：** `feat: 接入向量子系统生命周期与 HTTP 路由`

---

### Task 7: 扩展 capability/UI 契约以支持“已实现但需外部配置”

**Files:**

- Modify: `transform/schemas/capability-contract.schema.json`
- Modify: `transform/no-node/capabilities.json`
- Modify: `scripts/stapk-verify-capability-contract.mjs`
- Modify: `scripts/stapk-transform-no-node.mjs`
- Modify: `scripts/stapk-verify-ui-capability-contract.mjs`
- Modify: `test/no-node/task11-capability-runtime.test.mjs`
- Create: `test/no-node/task13-vector-capability-contract.test.mjs`
- Modify: `transform/no-node/ui-capabilities.json`
- Modify: `transform/no-node/mvp-api-allowlist.json`

**Contract change:**

`remote.embeddings` 仍保持 `kind: external_optional`、`defaultStatus: external_optional`、`uiPolicy: visible_when_configured`，但新增：

```json
"runtimeAvailable": true
```

该字段表示“APK 已包含本地适配能力”，不表示“用户已配置远程 key”。其他 `remote.*` 继续默认 false。

`remote.embeddings.endpointPrefixes` 新增 `/api/stapk/embeddings`，使三个受控配置接口与七个 vector 接口归入同一 capability。

UI contract 新增顶层数组：

```json
"configuredActions": [
  {
    "name": "Vector Storage",
    "selector": "#vectors_container",
    "capability": "remote.embeddings",
    "endpoint": "POST /api/vector/query",
    "source": {"type":"html","path":"index.html"}
  }
]
```

- [x] **Step 1: 先写 capability runtime 失败测试**

```javascript
test('runtime enables only core and explicit runtimeAvailable capabilities', async () => {
  const runtime = buildCapabilityRuntime({ capabilities: [
    { id: 'core.settings', kind: 'core' },
    { id: 'remote.embeddings', kind: 'external_optional', runtimeAvailable: true },
    { id: 'remote.tts', kind: 'external_optional' },
  ] });
  assert.equal(runtime.capabilities['core.settings'], true);
  assert.equal(runtime.capabilities['remote.embeddings'], true);
  assert.equal(runtime.capabilities['remote.tts'], false);
});
```

将 `copyCapabilityRuntime` 中的纯计算抽成 exported `buildCapabilityRuntime`。

- [x] **Step 2: 扩展 JSON schema 和 verifier fail-closed 规则**

`runtimeAvailable` 可选 Boolean；只允许 `external_optional` 设为 true，core 由 kind 隐式 true，excluded 严禁 true。添加反例测试保证 unknown field/错 kind 仍失败。

- [x] **Step 3: 先写 `configuredActions` verifier 失败测试**

它必须：

- 只引用 `external_optional + runtimeAvailable=true` capability；
- endpoint 在最终 `api-contract.json` 中为 `implemented`；
- selector 存在于最终 HTML；
- selector 不得再出现于 hiddenSelectors/CSS hidden catalog；
- source 验证复用 implementedActions 的 HTML/JS 规则。

- [x] **Step 4: 将七个 vector route 和三个配置 route 加入 allowlist**

```json
{"method":"POST","path":"/api/vector/list","reason":"Task 13 本地 SQLite 向量 hash 列表"}
```

同样添加 insert/delete/query/query-multi/purge/purge-all，以及 config/get、config/save、test。fixed patch 会 fetch 三个配置路由，所以它们必须在最终 contract 中同样为 `implemented`。

- [x] **Step 5: 更新 UI contract，移除 vector hidden selector**

从 `hiddenSelectors` 删除单独 `#vectors_container`，并修改宽泛 selector：

```css
#extensions_settings2 .extension_container:not(#qr_container):not(#regex_container):not(#summarize_container):not(#vectors_container)
```

将 Vector Storage 加入 `configuredActions`。

- [x] **Step 6: 运行 Task 7 契约测试**

```powershell
node --test test/no-node/task11-capability-runtime.test.mjs test/no-node/task13-vector-capability-contract.test.mjs
node scripts/stapk-verify-capability-contract.mjs --contract mobile/app/src/main/assets/api-contract.json --capabilities transform/no-node/capabilities.json
```

第二条在新 transform 前可因 assets 仍是旧契约而失败，但 schema/unit tests 必须 PASS；生成物验证在 Task 9 收敛。

**建议提交（由 Master 手动执行）：** `feat: 扩展远程可选能力与 UI 契约`

---

### Task 8: 制作 fixed patch，只暴露 OpenAI 与 Custom Provider

**Files:**

- Create: `patches/sillytavern-no-node/0012-stapk-mobile-remote-embedding-vector-storage.patch`
- Modify: `patches/sillytavern-no-node/series`
- Create: `test/no-node/task13-vector-storage-patch.test.mjs`
- Modify: `transform/no-node/web/stapk-capabilities.js` only if the patch uses a new helper API

**Patched upstream files recorded inside 0012:**

- `public/scripts/extensions/vectors/settings.html`
- `public/scripts/extensions/vectors/index.js`
- `public/css/stapk-mobile.css`

- [x] **Step 1: 先写 patch 结果失败测试**

测试使用现有 patch-queue helper 复制/施加到 fixture 或检查 transform 的 patched tree，断言：

- `#vectors_source` 只有 `openai` 和 `stapk_openai_compatible`；
- 默认 source 是 `openai`，不是 `transformers`/`local`；
- `getVectorsRequestBody` 对两种 source 都携带当前 model；
- Custom 配置只通过 `/api/stapk/embeddings/config/*`，普通 vector body 不携带 URL/key；
- 勾选 chats/files/World Info 向量功能前必须完成一次隐私确认；
- capability 未就绪/加载失败时开关 fail closed。

- [x] **Step 2: 在临时 patched tree 中做最小前端修改**

不直接编辑生成 assets。UI 增加：Custom Base URL、model、key（password input）、保存、测试连接、隐私提示。OpenAI 保留官方 model select，key 继续复用已有 secrets UI/state。

设置交互必须等待 config save 成功后才开启 vector switch；保存失败恢复原 selector/value，不让前端与 native config 分叉。

隐私确认状态固定保存为 `extension_settings.vectors.stapk_embedding_privacy_acknowledged=true`；只在用户点击确认后写入，取消时立即把本次尝试开启的 chats/files/World Info switch 恢复为 false。

同一 patch 从 `public/css/stapk-mobile.css` 的 `display:none!important` 组中删除 `#vectors_container`，并将宽泛 extension selector 改为明确排除 `#vectors_container`；不用 JavaScript 在运行时覆盖 CSS 隐藏。

- [x] **Step 3: 生成 fixed patch 并审阅范围**

使用现有 patch tooling 生成 `0012-stapk-mobile-remote-embedding-vector-storage.patch`，在 `series` 最后新增一行。审阅：

```powershell
git diff -- patches/sillytavern-no-node/series patches/sillytavern-no-node/0012-stapk-mobile-remote-embedding-vector-storage.patch
rg -n 'transformers|extras|webllm|ollama|apiKey|baseUrl' patches/sillytavern-no-node/0012-stapk-mobile-remote-embedding-vector-storage.patch
```

Expected: 其他 Provider 只能出现在删除行/防御性 fail-closed 代码中；没有把 key 写入 `extension_settings`。

- [x] **Step 4: 运行 patch 契约测试**

```powershell
node --test test/no-node/task13-vector-storage-patch.test.mjs
```

Expected: PASS。

**建议提交（由 Master 手动执行）：** `feat: 恢复 Vector Storage 并接入远程 Embedding 配置`

---

### Task 9: 重生成 no-node assets 并收敛契约门禁

**Files:**

- Regenerate: `mobile/app/src/main/assets/sillytavern-web/**`
- Regenerate: `mobile/app/src/main/assets/api-contract.json`
- Regenerate: `mobile/app/src/main/assets/stapk-web-manifest.json`
- Modify: `test/no-node/no-node-transform.test.mjs`
- Modify: `test/no-node/task11-capability-runtime.test.mjs`
- Modify: `test/no-node/task6-capabilities.test.mjs`

- [x] **Step 1: 先跑 no-node unit suite，修复所有非生成物失败**

```powershell
npm run test:no-node
```

Expected before transform: 允许只有明确指向旧 generated assets 的断言失败；其他必须先收敛。

- [x] **Step 2: 运行唯一正式 transform 生成路径**

```powershell
npm run transform:no-node
```

不手改 `generatedAt`以外的任何 generated file；如只有 timestamp 漂移，按项目现有规则恢复无关 timestamp 噪声。

- [x] **Step 3: 运行全部 transform/capability/UI verifier**

```powershell
npm run transform:no-node:verify
npm run verify:no-node-capabilities
npm run verify:no-node-ui
npm run test:no-node
```

Expected:

- 七个 `/api/vector/*` 全部 status=`implemented`；
- `remote.embeddings=true`，其他未实现 `remote.*=false`；
- `#vectors_container` 不在 hidden catalog，并位于 configuredActions；
- 无 visible `needs_review`；
- transform report 继续 `noRuntimeNode=true`。

- [x] **Step 4: 扫描生成物中的禁止路线**

```powershell
rg -n -i 'node_modules|server\.js|onnx|faiss|hnsw|sqlite-vss|sqlite-vec|transformers\.js' mobile/app/src/main/assets transform/no-node patches/sillytavern-no-node
```

逐条归因命中；测试字符/删除行可以存在，APK payload/runtime 不得存在。

- [x] **Step 5: 运行 Android JVM 全套**

```powershell
.\mobile\gradlew.bat --no-daemon -p mobile :app:testDebugUnitTest
```

Expected: PASS。

**建议提交（由 Master 手动执行）：** `build: 重生成向量功能的 no-node Web 资产`

---

### Task 10: 加入 Android 仪器化性能/持久化测试并构建 APK

**Files:**

- Modify: `mobile/app/build.gradle.kts`
- Create: `mobile/app/src/androidTest/java/com/stapk/mobile/nativeadapter/vector/VectorStoreInstrumentedTest.kt`
- Create: `mobile/app/src/androidTest/java/com/stapk/mobile/nativeadapter/vector/VectorStorePerformanceInstrumentedTest.kt`

- [x] **Step 1: 新增最小 androidTest 依赖**

```kotlin
defaultConfig {
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}

androidTestImplementation("androidx.test.ext:junit:1.1.5")
androidTestImplementation("androidx.test:runner:1.5.2")
```

不引入 benchmark plugin 或 native profiler 依赖。

- [x] **Step 2: 先写设备持久化与 WAL 测试**

在 instrumentation context 中创建 DB，写入后 close/reopen，确认 query/list 持久化；purge-all 后 canonical fixture 文件仍存在。测试 finally 只删除独立 test database 的明确路径。

- [x] **Step 3: 写 Pixel 8/API 35 性能基线测试**

用确定性 PRNG seed 流式生成 `10000 x 1536` 归一化向量，每批写入后立即丢弃该批数组，不在 heap 中保留 10000 条向量。在计时前完成 DB warm-up/GC；只计时本地 `repository.query(topK=10)`，不包括数据生成、写入或远程 HTTP。

```kotlin
assertTrue("scan took ${elapsedMs}ms", elapsedMs <= 5_000L)
assertTrue("heap grew ${heapDelta} bytes", heapDelta <= 64L * 1024L * 1024L)
```

若 emulator 负载导致一次超时，先保留 raw 数据运行 3 次取中位数；不放宽 5s/64MiB 标准来“让测试变绿”。

- [x] **Step 4: 在 API 35 emulator 运行 instrumentation**

```powershell
.\mobile\gradlew.bat --no-daemon -p mobile :app:connectedDebugAndroidTest
```

Expected: 功能测试 PASS，性能中位数 `<=5s`，查询新增 heap `<=64MiB`。

实际证据：使用直接 `adb shell am instrument` 运行 `AndroidJUnitRunner`，3/3 PASS；性能中位数 `2578 ms`、heap delta `3,497,984 bytes`。`connectedDebugAndroidTest` 未作为最终证据路径。

- [x] **Step 5: 构建 no-node Debug APK**

```powershell
npm run build:no-node-apk
```

Expected: 构建成功，输出 APK 不含 Node/runtime model/native vector library。

- [x] **Step 6: 审查 APK 内容**

使用 Android SDK `apkanalyzer`/`aapt` 列出 APK，检查不存在 `node`、`node_modules`、`.onnx`、FAISS/HNSW/sqlite-vector `.so`；只允许 Android 系统 SQLite API 调用。

**建议提交（由 Master 手动执行）：** `test: 添加向量存储设备与性能验证`

---

### Task 11: 完成 Data Bank/聊天/World Info 设备矩阵验收

**Files:**

- Create: `docs/plan/2026-07-30-stapk-vector-storage-validation-record.md`
- Modify after evidence: `docs/superpowers/specs/2026-07-30-stapk-remote-embedding-local-vector-design.md`
- Modify if user-facing docs mention capability: `CLAUDE.md`
- Modify if user-facing docs mention feature: `README.md`

- [x] **Step 1: 创建证据记录模板，不预填 PASS**

记录每个设备的 API level、ABI、APK SHA-256、Provider 类型/脱敏 host/model fingerprint、用例、实际结果、logcat/截图路径。状态只能是 `PASS`/`FAIL`/`BLOCKED`。

- [x] **Step 2: API 35 完整功能验收**

1. clean install，配置 Custom OpenAI-compatible Provider，测试连接显示维度；
2. Data Bank 全局/角色/聊天范围分别加入可识别文本；
3. Vectorize All，提问并通过最终 request/debug Prompt 确认 chunk 被注入；
4. 创建长聊天，证明可召回旧消息；
5. 开启 World Info 向量激活，证明对应 entry 命中；
6. force-stop/relaunch 后直接查询旧索引；
7. 切换 model，list 对新 namespace 为空并提示重新向量化；
8. 断网/429/500 后断言 list 无半批 hash；
9. purge/purge-all 后聊天、附件、World Info 未删除；
10. `ps -A`/APK 内容再次确认无 Node 或本地 embedding 进程。

- [x] **Step 3: 确认 Android 支持矩阵只覆盖 API 35 及以上**

项目继续保持既有 `minSdk=24` 和工具链版本，但正式维护、回归和问题修复只覆盖 API 35 及以上；低版本可安装不等于受支持，也不再要求补充 API 24/29 设备证据。

- [x] **Step 4: 扫描隐私与错误输出**

使用特殊 canary 作为 Data Bank text/key，在以下位置搜索：app logs、logcat、diagnostics export、HTTP error body、Provider 错误 UI。只允许 Provider MockWebServer 的请求体中出现用户文本；key 只允许 Authorization header。

- [x] **Step 5: 运行最终自动化门禁**

```powershell
npm run test:no-node
npm run transform:no-node:verify
npm run verify:no-node-capabilities
npm run verify:no-node-ui
.\mobile\gradlew.bat --no-daemon -p mobile :app:testDebugUnitTest
npm run build:no-node-apk
git status --short
```

分开报告 focused tests、full JVM suite、Node/no-node suite、instrumentation matrix、APK build、真机/模拟器验收；任一类缺失都不宣称“完成”。

- [x] **Step 6: 在 API 35 证据全部通过后更新文档状态**

将设计规格状态从“已确认，待实施”改为“已实施并验收”，在 README/CLAUDE 写清：远程 embedding 可能计费、文本会发送给用户配置的 Provider、向量 DB 为可重建索引。

**建议提交（由 Master 手动执行）：** `docs: 记录向量存储设备矩阵验收`

---

## Final Completion Gate

- [x] OpenAI 和 Custom OpenAI-compatible 都能在官方 Vector Storage UI 中配置，其他 Provider fail closed。Custom 已完成设备验证；OpenAI 已完成 JVM/UI contract 验证。
- [x] Data Bank、聊天记忆、World Info 三条真实路径通过 API 35 设备验收。
- [x] 七个 `/api/vector/*` 与当前 SillyTavern 请求/响应形状一致，contract 状态全为 `implemented`。
- [x] 384/768/1024/1536/3072/32768 维 codec 通过，endpoint/model/dimension namespace 不混用。
- [x] Provider 错误、空间不足、维度变化、并发 purge/insert 无部分写入。
- [x] DB 损坏会隔离 DB/WAL/SHM、创建空 schema 并要求显式重建，不自动产生 Provider 费用。
- [x] 日志/error response 不含 API key、Authorization、Base URL、用户文本、metadata.text 或向量。
- [x] Pixel 8/API 35 上 10000 x 1536 Top-10 中位数 `<=5s`，新增 heap `<=64MiB`。
- [x] API 35 及以上维护策略有明确记录，API 35 功能矩阵有可追溯证据。
- [x] no-node tests、capability/UI verifier、Android JVM tests、instrumentation 和 Debug APK build 全部通过。
- [x] APK 与运行进程不包含 Node.js、本地 embedding 模型、独立向量 DB 服务或 native vector library。
